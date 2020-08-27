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

import com.google.common.base.Preconditions;
import io.grpc.ServerBuilder;
import io.grpc.ServerStreamTracer;
import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;

public final class ServerImplBuilder extends AbstractServerImplBuilder<ServerImplBuilder> {
  private final ClientTransportServersBuilder clientTransportServersBuilder;

  /**
   * TODO(sergiitk): javadoc.
   */
  public interface ClientTransportServersBuilder {
    List<? extends InternalServer> buildClientTransportServers(
        List<? extends ServerStreamTracer.Factory> streamTracerFactories);
  }

  /**
   * TODO(sergiitk): javadoc.
   */
  public ServerImplBuilder(ClientTransportServersBuilder clientTransportServersBuilder) {
    this.clientTransportServersBuilder = Preconditions.checkNotNull(clientTransportServersBuilder,
        "clientTransportServersBuilder cannot be null");
  }

  @Override
  protected List<? extends InternalServer> buildTransportServers(
      List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
    return clientTransportServersBuilder.buildClientTransportServers(streamTracerFactories);
  }

  @Override
  public ServerImplBuilder useTransportSecurity(File certChain, File privateKey) {
    // TODO(sergiitk): Implement
    return null;
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
  public void setStatsRecordRealTimeMetrics(boolean value) {
    super.setStatsRecordRealTimeMetrics(value);
  }

  @Override
  public ObjectPool<? extends Executor> getExecutorPool() {
    return super.getExecutorPool();
  }

  public static ServerBuilder<?> forPort(int port) {
    // TODO(sergiitk): Update message based on constructor
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

}
