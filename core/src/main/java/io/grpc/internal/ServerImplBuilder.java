/*
 * Copyright 2020 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.BinaryLog;
import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.InternalChannelz;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Default builder for {@link io.grpc.Server} instances, for usage in Transport implementations.
 */
public final class ServerImplBuilder extends AbstractServerImplBuilder<ServerImplBuilder> {
  private final ClientTransportServersBuilder clientTransportServersBuilder;

  /**
   * An interface to provide to provide transport specific information for the server. This method
   * is meant for Transport implementors and should not be used by normal users.
   */
  public interface ClientTransportServersBuilder {
    List<? extends InternalServer> buildClientTransportServers(
        List<? extends ServerStreamTracer.Factory> streamTracerFactories);
  }

  /**
   * Creates a new server builder with given transport servers provider.
   */
  public ServerImplBuilder(ClientTransportServersBuilder clientTransportServersBuilder) {
    this.clientTransportServersBuilder = Preconditions
        .checkNotNull(clientTransportServersBuilder, "clientTransportServersBuilder");
  }

  @Override
  public Server build() {
    return new ServerImpl(this, buildTransportServers(getTracerFactories()), Context.ROOT);
  }

  @Override
  protected List<? extends InternalServer> buildTransportServers(
      List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
    return clientTransportServersBuilder.buildClientTransportServers(streamTracerFactories);
  }

  @Override
  public ServerImplBuilder directExecutor() {
    return executor(MoreExecutors.directExecutor());
  }

  @Override
  public ServerImplBuilder executor(@Nullable Executor executor) {
    this.executorPool = executor != null ? new FixedObjectPool<>(executor) : DEFAULT_EXECUTOR_POOL;
    return this;
  }

  @Override
  public ServerImplBuilder addService(ServerServiceDefinition service) {
    registryBuilder.addService(checkNotNull(service, "service"));
    return this;
  }

  @Override
  public ServerImplBuilder addService(BindableService bindableService) {
    return addService(checkNotNull(bindableService, "bindableService").bindService());
  }

  @Override
  public ServerImplBuilder addTransportFilter(ServerTransportFilter filter) {
    transportFilters.add(checkNotNull(filter, "filter"));
    return this;
  }

  @Override
  public ServerImplBuilder intercept(ServerInterceptor interceptor) {
    interceptors.add(checkNotNull(interceptor, "interceptor"));
    return this;
  }

  @Override
  public ServerImplBuilder addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    streamTracerFactories.add(checkNotNull(factory, "factory"));
    return this;
  }

  @Override
  public ServerImplBuilder fallbackHandlerRegistry(@Nullable HandlerRegistry registry) {
    this.fallbackRegistry = registry != null ? registry : DEFAULT_FALLBACK_REGISTRY;
    return this;
  }

  @Override
  public ServerImplBuilder decompressorRegistry(@Nullable DecompressorRegistry registry) {
    this.decompressorRegistry = registry != null ? registry : DEFAULT_DECOMPRESSOR_REGISTRY;
    return this;
  }

  @Override
  public ServerImplBuilder compressorRegistry(@Nullable CompressorRegistry registry) {
    this.compressorRegistry = registry != null ? registry : DEFAULT_COMPRESSOR_REGISTRY;
    return this;
  }

  @Override
  public ServerImplBuilder handshakeTimeout(long timeout, TimeUnit unit) {
    checkArgument(timeout > 0, "handshake timeout is %s, but must be positive", timeout);
    this.handshakeTimeoutMillis = checkNotNull(unit, "unit").toMillis(timeout);
    return this;
  }

  @Override
  public ServerImplBuilder setBinaryLog(@Nullable BinaryLog binaryLog) {
    this.binlog = binaryLog;
    return this;
  }

  @VisibleForTesting
  public ServerImplBuilder setTransportTracerFactory(
      TransportTracer.Factory transportTracerFactory) {
    this.transportTracerFactory = transportTracerFactory;
    return this;
  }

  @Override
  public void setDeadlineTicker(Deadline.Ticker ticker) {
    super.setDeadlineTicker(ticker);
  }

  @Override
  public void setTracingEnabled(boolean value) {
    super.setTracingEnabled(value);
  }

  @Override
  public void setStatsEnabled(boolean value) {
    super.setStatsEnabled(value);
  }

  @Override
  public void setStatsRecordStartedRpcs(boolean value) {
    super.setStatsRecordStartedRpcs(value);
  }

  @Override
  public void setStatsRecordFinishedRpcs(boolean value) {
    super.setStatsRecordFinishedRpcs(value);
  }

  @Override
  public void setStatsRecordRealTimeMetrics(boolean value) {
    super.setStatsRecordRealTimeMetrics(value);
  }

  @Override
  public InternalChannelz getChannelz() {
    return super.getChannelz();
  }

  @Override
  public ObjectPool<? extends Executor> getExecutorPool() {
    return super.getExecutorPool();
  }

  @Override
  public ServerImplBuilder useTransportSecurity(File certChain, File privateKey) {
    throw new UnsupportedOperationException("TLS not supported in ServerImplBuilder");
  }

  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException(
        "ClientTransportServersBuilder is required, use a constructor");
  }
}
