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

// we won't be able to override checkAuthority anymore - bury it here
public class ManagedChannelImplBuilder
    extends AbstractManagedChannelImplBuilder<ManagedChannelImplBuilder> {
  public interface ClientTransportFactoryFactory {
    ClientTransportFactory buildTransportFactory();
  }

  private final ClientTransportFactoryFactory clientTransportFactoryFactory;
  private int defaultPort;

  /**
   * Creates a new builder for the given target that will be resolved by
   * {@link io.grpc.NameResolver}.
   */
  public ManagedChannelImplBuilder(String target,
      ClientTransportFactoryFactory clientTransportFactoryFactory) {
    // TODO(sergiitk): finish docblock
    // TODO(sergiitk): one more for SocketAddress
    super(target);
    this.clientTransportFactoryFactory = clientTransportFactoryFactory;
    this.defaultPort = super.getDefaultPort();
  }

  @Override
  protected ClientTransportFactory buildTransportFactory() {
    // set the port
    return clientTransportFactoryFactory.buildTransportFactory();
  }

  @Override
  public int getDefaultPort() {
    return defaultPort;
  }

  public void setDefaultPort(int defaultPort) {
    this.defaultPort = defaultPort;
  }

  @Override
  public int maxInboundMessageSize() {
    return super.maxInboundMessageSize();
  }
}
