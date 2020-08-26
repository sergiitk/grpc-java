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

import io.grpc.ServerBuilder;
import io.grpc.ServerStreamTracer;
import java.io.File;
import java.net.SocketAddress;
import java.util.List;

public class ServerImplBuilder extends AbstractServerImplBuilder<ServerImplBuilder> {

  public ServerImplBuilder() {

  }

  @Override
  protected List<? extends InternalServer> buildTransportServers(
      List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
    // TODO(sergiitk): Implement
    return null;
  }

  @Override
  public ServerImplBuilder useTransportSecurity(File certChain, File privateKey) {
    // TODO(sergiitk): Implement
    return null;
  }

  public static ServerBuilder<?> forPort(int port) {
    // TODO(sergiitk): Update message based on constructor
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

}
