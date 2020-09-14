/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.BinaryLog;
import io.grpc.CompressorRegistry;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.InternalChannelz;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * The base class for server builders.
 *
 * @param <T> The concrete type for this builder.
 */
public abstract class AbstractServerImplBuilder<T extends AbstractServerImplBuilder<T>>
        extends ServerBuilder<T> {

  private static final Logger log = Logger.getLogger(AbstractServerImplBuilder.class.getName());

  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

  // defaults
  protected static final ObjectPool<? extends Executor> DEFAULT_EXECUTOR_POOL =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
  protected static final HandlerRegistry DEFAULT_FALLBACK_REGISTRY = new DefaultFallbackRegistry();
  protected static final DecompressorRegistry DEFAULT_DECOMPRESSOR_REGISTRY =
      DecompressorRegistry.getDefaultInstance();
  protected static final CompressorRegistry DEFAULT_COMPRESSOR_REGISTRY =
      CompressorRegistry.getDefaultInstance();
  protected static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(120);

  // mutable state
  final InternalHandlerRegistry.Builder registryBuilder =
      new InternalHandlerRegistry.Builder();
  final List<ServerTransportFilter> transportFilters = new ArrayList<>();
  final List<ServerInterceptor> interceptors = new ArrayList<>();
  protected final List<ServerStreamTracer.Factory> streamTracerFactories = new ArrayList<>();
  HandlerRegistry fallbackRegistry = DEFAULT_FALLBACK_REGISTRY;
  ObjectPool<? extends Executor> executorPool = DEFAULT_EXECUTOR_POOL;
  DecompressorRegistry decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;
  CompressorRegistry compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;
  long handshakeTimeoutMillis = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
  Deadline.Ticker ticker = Deadline.getSystemTicker();
  protected boolean statsEnabled = true;
  protected boolean recordStartedRpcs = true;
  protected boolean recordFinishedRpcs = true;
  protected boolean recordRealTimeMetrics = false;
  protected boolean tracingEnabled = true;
  @Nullable BinaryLog binlog;
  TransportTracer.Factory transportTracerFactory = TransportTracer.getDefaultFactory();
  InternalChannelz channelz = InternalChannelz.instance();
  CallTracer.Factory callTracerFactory = CallTracer.getDefaultFactory();

  /**
   * Disable or enable stats features.  Enabled by default.
   */
  protected void setStatsEnabled(boolean value) {
    this.statsEnabled = value;
  }

  /**
   * Disable or enable stats recording for RPC upstarts.  Effective only if {@link
   * #setStatsEnabled} is set to true.  Enabled by default.
   */
  protected void setStatsRecordStartedRpcs(boolean value) {
    recordStartedRpcs = value;
  }

  /**
   * Disable or enable stats recording for RPC completions.  Effective only if {@link
   * #setStatsEnabled} is set to true.  Enabled by default.
   */
  protected void setStatsRecordFinishedRpcs(boolean value) {
    recordFinishedRpcs = value;
  }

  /**
   * Disable or enable real-time metrics recording.  Effective only if {@link #setStatsEnabled} is
   * set to true.  Disabled by default.
   */
  protected void setStatsRecordRealTimeMetrics(boolean value) {
    recordRealTimeMetrics = value;
  }

  /**
   * Disable or enable tracing features.  Enabled by default.
   */
  protected void setTracingEnabled(boolean value) {
    tracingEnabled = value;
  }

  /**
   * Sets a custom deadline ticker.  This should only be called from InProcessServerBuilder.
   */
  protected void setDeadlineTicker(Deadline.Ticker ticker) {
    this.ticker = checkNotNull(ticker, "ticker");
  }

  @VisibleForTesting
  final List<? extends ServerStreamTracer.Factory> getTracerFactories() {
    ArrayList<ServerStreamTracer.Factory> tracerFactories = new ArrayList<>();
    if (statsEnabled) {
      ServerStreamTracer.Factory censusStatsTracerFactory = null;
      try {
        Class<?> censusStatsAccessor =
            Class.forName("io.grpc.census.InternalCensusStatsAccessor");
        Method getServerStreamTracerFactoryMethod =
            censusStatsAccessor.getDeclaredMethod(
                "getServerStreamTracerFactory",
                boolean.class,
                boolean.class,
                boolean.class);
        censusStatsTracerFactory =
            (ServerStreamTracer.Factory) getServerStreamTracerFactoryMethod
                .invoke(
                    null,
                    recordStartedRpcs,
                    recordFinishedRpcs,
                    recordRealTimeMetrics);
      } catch (ClassNotFoundException e) {
        // Replace these separate catch statements with multicatch when Android min-API >= 19
        log.log(Level.FINE, "Unable to apply census stats", e);
      } catch (NoSuchMethodException e) {
        log.log(Level.FINE, "Unable to apply census stats", e);
      } catch (IllegalAccessException e) {
        log.log(Level.FINE, "Unable to apply census stats", e);
      } catch (InvocationTargetException e) {
        log.log(Level.FINE, "Unable to apply census stats", e);
      }
      if (censusStatsTracerFactory != null) {
        tracerFactories.add(censusStatsTracerFactory);
      }
    }
    if (tracingEnabled) {
      ServerStreamTracer.Factory tracingStreamTracerFactory = null;
      try {
        Class<?> censusTracingAccessor =
            Class.forName("io.grpc.census.InternalCensusTracingAccessor");
        Method getServerStreamTracerFactoryMethod =
            censusTracingAccessor.getDeclaredMethod("getServerStreamTracerFactory");
        tracingStreamTracerFactory =
            (ServerStreamTracer.Factory) getServerStreamTracerFactoryMethod.invoke(null);
      } catch (ClassNotFoundException e) {
        // Replace these separate catch statements with multicatch when Android min-API >= 19
        log.log(Level.FINE, "Unable to apply census stats", e);
      } catch (NoSuchMethodException e) {
        log.log(Level.FINE, "Unable to apply census stats", e);
      } catch (IllegalAccessException e) {
        log.log(Level.FINE, "Unable to apply census stats", e);
      } catch (InvocationTargetException e) {
        log.log(Level.FINE, "Unable to apply census stats", e);
      }
      if (tracingStreamTracerFactory != null) {
        tracerFactories.add(tracingStreamTracerFactory);
      }
    }
    tracerFactories.addAll(streamTracerFactories);
    tracerFactories.trimToSize();
    return Collections.unmodifiableList(tracerFactories);
  }

  protected InternalChannelz getChannelz() {
    return channelz;
  }

  protected final TransportTracer.Factory getTransportTracerFactory() {
    return transportTracerFactory;
  }

  /**
   * Children of AbstractServerBuilder should override this method to provide transport specific
   * information for the server.  This method is mean for Transport implementors and should not be
   * used by normal users.
   *
   * @param streamTracerFactories an immutable list of stream tracer factories
   */
  protected abstract List<? extends io.grpc.internal.InternalServer> buildTransportServers(
      List<? extends ServerStreamTracer.Factory> streamTracerFactories);

  private static final class DefaultFallbackRegistry extends HandlerRegistry {
    @Override
    public List<ServerServiceDefinition> getServices() {
      return Collections.emptyList();
    }

    @Nullable
    @Override
    public ServerMethodDefinition<?, ?> lookupMethod(
        String methodName, @Nullable String authority) {
      return null;
    }
  }

  /**
   * Returns the internal ExecutorPool for offloading tasks.
   */
  protected ObjectPool<? extends Executor> getExecutorPool() {
    return this.executorPool;
  }
}
