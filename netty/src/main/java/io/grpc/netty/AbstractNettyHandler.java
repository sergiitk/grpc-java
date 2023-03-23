/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND;
import static io.netty.handler.codec.http2.Http2CodecUtil.getEmbeddedHttp2Exception;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Ticker;
import io.grpc.ChannelLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import java.util.concurrent.TimeUnit;

/**
 * Base class for all Netty gRPC handlers. This class standardizes exception handling (always
 * shutdown the connection) as well as sending the initial connection window at startup.
 */
abstract class AbstractNettyHandler extends GrpcHttp2ConnectionHandler {
  private static final long GRACEFUL_SHUTDOWN_NO_TIMEOUT = -1;

  private final int initialConnectionWindow;
  private final FlowControlPinger flowControlPing;
  private int windowSizeToFrameRatio;

  private boolean autoTuneFlowControlOn;
  private ChannelHandlerContext ctx;
  private boolean initialWindowSent = false;
  private final Ticker ticker;

  private static final long BDP_MEASUREMENT_PING = 1234;
  private final Http2FrameReader frameReader;

  AbstractNettyHandler(
      ChannelPromise channelUnused,
      Http2ConnectionDecoder decoder,
      Http2ConnectionEncoder encoder,
      Http2FrameReader frameReader,
      Http2Settings initialSettings,
      ChannelLogger negotiationLogger,
      boolean autoFlowControl,
      PingLimiter pingLimiter,
      Ticker ticker) {
    super(channelUnused, decoder, encoder, initialSettings, negotiationLogger);
    this.frameReader = frameReader;

    // During a graceful shutdown, wait until all streams are closed.
    gracefulShutdownTimeoutMillis(GRACEFUL_SHUTDOWN_NO_TIMEOUT);

    // Extract the connection window from the settings if it was set.
    this.initialConnectionWindow = initialSettings.initialWindowSize() == null ? -1 :
        initialSettings.initialWindowSize();
    this.autoTuneFlowControlOn = autoFlowControl;
    if (pingLimiter == null) {
      pingLimiter = new AllowPingLimiter();
    }
    this.flowControlPing = new FlowControlPinger(pingLimiter);
    this.ticker = checkNotNull(ticker, "ticker");
    this.windowSizeToFrameRatio =
        Integer.parseInt(System.getenv().getOrDefault("GRPC_WINDOW_TO_FRAME_RATIO", "0"));
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    this.ctx = ctx;
    // Sends the connection preface if we haven't already.
    super.handlerAdded(ctx);
    sendInitialConnectionWindow();
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    // Sends connection preface if we haven't already.
    super.channelActive(ctx);
    sendInitialConnectionWindow();
  }

  @Override
  public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    Http2Exception embedded = getEmbeddedHttp2Exception(cause);
    if (embedded == null) {
      // There was no embedded Http2Exception, assume it's a connection error. Subclasses are
      // responsible for storing the appropriate status and shutting down the connection.
      onError(ctx, /* outbound= */ false, cause);
    } else {
      super.exceptionCaught(ctx, cause);
    }
  }

  protected final ChannelHandlerContext ctx() {
    return ctx;
  }

  /**
   * Sends initial connection window to the remote endpoint if necessary.
   */
  private void sendInitialConnectionWindow() throws Http2Exception {
    if (!initialWindowSent && ctx.channel().isActive()) {
      Http2Stream connectionStream = connection().connectionStream();
      int currentSize = connection().local().flowController().windowSize(connectionStream);
      int delta = initialConnectionWindow - currentSize;
      decoder().flowController().incrementWindowSize(connectionStream, delta);
      initialWindowSent = true;
      ctx.flush();
    }
  }

  @VisibleForTesting
  FlowControlPinger flowControlPing() {
    return flowControlPing;
  }

  @VisibleForTesting
  void setAutoTuneFlowControl(boolean isOn) {
    autoTuneFlowControlOn = isOn;
  }

  @VisibleForTesting
  void setWindowToFrameRatio(int ratio) {
    windowSizeToFrameRatio = ratio;
  }

  /**
   * Class for handling flow control pinging and flow control window updates as necessary.
   */
  final class FlowControlPinger {

    private static final int MAX_WINDOW_SIZE = 8 * 1024 * 1024;
    public static final int MAX_BACKOFF = 10;

    private final PingLimiter pingLimiter;
    private int pingCount;
    private int pingReturn;
    private boolean pinging;
    private int dataSizeSincePing;
    private long lastBandwidth; // bytes per nanosecond
    private long lastPingTime;
    private int lastTargetWindow;
    private int pingFrequencyMultiplier;

    public FlowControlPinger(PingLimiter pingLimiter) {
      Preconditions.checkNotNull(pingLimiter, "pingLimiter");
      this.pingLimiter = pingLimiter;
    }

    public long payload() {
      return BDP_MEASUREMENT_PING;
    }

    public int maxWindow() {
      return MAX_WINDOW_SIZE;
    }

    public void onDataRead(int dataLength, int paddingLength) {
      if (!autoTuneFlowControlOn) {
        return;
      }

      // Note that we are double counting around the ping initiation as the current data will be
      // added at the end of this method, so will be available in the next check.  This at worst
      // causes us to send a ping one data packet earlier, but makes startup faster if there are
      // small packets before big ones.
      int dataForCheck = getDataSincePing() + dataLength + paddingLength;
      // Need to double the data here to account for targetWindow being set to twice the data below
      if (!isPinging() && pingLimiter.isPingAllowed()
          && dataForCheck * 2 >= lastTargetWindow * pingFrequencyMultiplier) {
        setPinging(true);
        sendPing(ctx());
      }

      if (lastTargetWindow == 0) {
        lastTargetWindow =
            decoder().flowController().initialWindowSize(connection().connectionStream());
      }

      incrementDataSincePing(dataLength + paddingLength);
    }

    public void updateWindow() throws Http2Exception {
      if (!autoTuneFlowControlOn) {
        return;
      }
      pingReturn++;
      setPinging(false);

      long elapsedTime = (ticker.read() - lastPingTime);
      if (elapsedTime == 0) {
        elapsedTime = 1;
      }

      long bandwidth = (getDataSincePing() * TimeUnit.SECONDS.toNanos(1)) / elapsedTime;
      // Calculate new window size by doubling the observed BDP, but cap at max window
      int targetWindow = Math.min(getDataSincePing() * 2, MAX_WINDOW_SIZE);
      Http2LocalFlowController fc = decoder().flowController();
      int currentWindow = fc.initialWindowSize(connection().connectionStream());
      if (bandwidth <= lastBandwidth || targetWindow <= currentWindow) {
        pingFrequencyMultiplier = Math.min(pingFrequencyMultiplier + 1, MAX_BACKOFF);
        return;
      }

      pingFrequencyMultiplier = 0; // react quickly when size is changing
      lastBandwidth = bandwidth;
      lastTargetWindow = targetWindow;
      int increase = targetWindow - currentWindow;
      fc.incrementWindowSize(connection().connectionStream(), increase);
      fc.initialWindowSize(targetWindow);
      Http2Settings settings = new Http2Settings();
      settings.initialWindowSize(targetWindow);
      if (windowSizeToFrameRatio > 0) {
        // Netty default, gRPC default: 0x4000 = 16,384 B = 16 KiB
        // Absolute maximum (inclusive): 0xFFFFFF = 16,777,215 B = (16 MiB - 1 B)
        // (defined in https://www.rfc-editor.org/rfc/rfc9113.html#SETTINGS_MAX_FRAME_SIZE)
        int frameSize = Math.min(targetWindow / windowSizeToFrameRatio, MAX_FRAME_SIZE_UPPER_BOUND);
        settings.maxFrameSize(frameSize);
        frameReader.configuration().frameSizePolicy().maxFrameSize(frameSize);

      }
      frameWriter().writeSettings(ctx(), settings, ctx().newPromise());
    }

    private boolean isPinging() {
      return pinging;
    }

    private void setPinging(boolean pingOut) {
      pinging = pingOut;
    }

    private void sendPing(ChannelHandlerContext ctx) {
      setDataSizeSincePing(0);
      lastPingTime = ticker.read();
      encoder().writePing(ctx, false, BDP_MEASUREMENT_PING, ctx.newPromise());
      pingCount++;
    }

    private void incrementDataSincePing(int increase) {
      int currentSize = getDataSincePing();
      setDataSizeSincePing(currentSize + increase);
    }

    @VisibleForTesting
    int getPingCount() {
      return pingCount;
    }

    @VisibleForTesting
    int getPingReturn() {
      return pingReturn;
    }

    @VisibleForTesting
    int getDataSincePing() {
      return dataSizeSincePing;
    }

    private void setDataSizeSincePing(int dataSize) {
      dataSizeSincePing = dataSize;
    }

    // Only used in testing
    @VisibleForTesting
    void setDataSizeAndSincePing(int dataSize) {
      setDataSizeSincePing(dataSize);
      pingFrequencyMultiplier = 1;
      lastPingTime = ticker.read() ;
    }
  }

  /** Controls whether PINGs like those for BDP are permitted to be sent at the current time. */
  public interface PingLimiter {
    boolean isPingAllowed();
  }

  private static final class AllowPingLimiter implements PingLimiter {
    @Override public boolean isPingAllowed() {
      return true;
    }
  }
}
