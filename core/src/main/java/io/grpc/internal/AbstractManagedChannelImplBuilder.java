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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.BinaryLog;
import io.grpc.ClientInterceptor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.InternalChannelz;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.NameResolverRegistry;
import io.grpc.ProxyDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Abstract base class for channel builders.
 *
 * @param <T> The concrete type of this builder.
 */
public abstract class AbstractManagedChannelImplBuilder
        <T extends AbstractManagedChannelImplBuilder<T>> extends ManagedChannelBuilder<T> {
  public static ManagedChannelBuilder<?> forAddress(String name, int port) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

  public static ManagedChannelBuilder<?> forTarget(String target) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

  /**
   * An idle timeout larger than this would disable idle mode.
   */
  @VisibleForTesting
  static final long IDLE_MODE_MAX_TIMEOUT_DAYS = 30;

  /**
   * The default idle timeout.
   */
  @VisibleForTesting
  static final long IDLE_MODE_DEFAULT_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

  /**
   * An idle timeout smaller than this would be capped to it.
   */
  static final long IDLE_MODE_MIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(1);

  protected static final ObjectPool<? extends Executor> DEFAULT_EXECUTOR_POOL =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);

  protected static final DecompressorRegistry DEFAULT_DECOMPRESSOR_REGISTRY =
      DecompressorRegistry.getDefaultInstance();

  protected static final CompressorRegistry DEFAULT_COMPRESSOR_REGISTRY =
      CompressorRegistry.getDefaultInstance();

  private static final long DEFAULT_RETRY_BUFFER_SIZE_IN_BYTES = 1L << 24;  // 16M
  private static final long DEFAULT_PER_RPC_BUFFER_LIMIT_IN_BYTES = 1L << 20; // 1M

  ObjectPool<? extends Executor> executorPool = DEFAULT_EXECUTOR_POOL;

  ObjectPool<? extends Executor> offloadExecutorPool = DEFAULT_EXECUTOR_POOL;

  protected final List<ClientInterceptor> interceptors = new ArrayList<>();
  final NameResolverRegistry nameResolverRegistry = NameResolverRegistry.getDefaultRegistry();

  // Access via getter, which may perform authority override as needed
  protected NameResolver.Factory nameResolverFactory = nameResolverRegistry.asFactory();

  @Nullable
  String userAgent;

  @VisibleForTesting
  @Nullable
  String authorityOverride;

  String defaultLbPolicy = GrpcUtil.DEFAULT_LB_POLICY;

  boolean fullStreamDecompression;

  DecompressorRegistry decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;

  CompressorRegistry compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;

  long idleTimeoutMillis = IDLE_MODE_DEFAULT_TIMEOUT_MILLIS;

  int maxRetryAttempts = 5;
  int maxHedgedAttempts = 5;
  long retryBufferSize = DEFAULT_RETRY_BUFFER_SIZE_IN_BYTES;
  long perRpcBufferLimit = DEFAULT_PER_RPC_BUFFER_LIMIT_IN_BYTES;
  boolean retryEnabled = false; // TODO(zdapeng): default to true
  // Temporarily disable retry when stats or tracing is enabled to avoid breakage, until we know
  // what should be the desired behavior for retry + stats/tracing.
  // TODO(zdapeng): delete me
  boolean temporarilyDisableRetry;

  InternalChannelz channelz = InternalChannelz.instance();
  int maxTraceEvents;

  @Nullable
  Map<String, ?> defaultServiceConfig;
  boolean lookUpServiceConfig = true;

  protected int maxInboundMessageSize = GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;

  @Nullable
  BinaryLog binlog;

  @Nullable
  ProxyDetector proxyDetector;

  protected boolean statsEnabled = true;
  protected boolean recordStartedRpcs = true;
  protected boolean recordFinishedRpcs = true;
  protected boolean recordRealTimeMetrics = false;
  protected boolean tracingEnabled = true;
}
