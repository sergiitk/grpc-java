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

import io.grpc.BinaryLog;
import io.grpc.CompressorRegistry;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The base class for server builders.
 *
 * @param <T> The concrete type for this builder.
 */
public abstract class AbstractServerImplBuilder<T extends AbstractServerImplBuilder<T>>
        extends ServerBuilder<T> {

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
  CallTracer.Factory callTracerFactory = CallTracer.getDefaultFactory();

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
