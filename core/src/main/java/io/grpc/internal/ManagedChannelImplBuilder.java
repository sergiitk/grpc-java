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

import java.net.SocketAddress;
import javax.annotation.Nullable;

// TODO(sergiitk): We won't be able to override checkAuthority anymore - bury it here
public final class ManagedChannelImplBuilder
    extends AbstractManagedChannelImplBuilder<ManagedChannelImplBuilder> {

  public interface ClientTransportFactoryBuilder {
    ClientTransportFactory buildClientTransportFactory(int maxInboundMessageSize);
  }

  public interface ChannelBuilderDefaultPortProvider {
    int getDefaultPort();
  }

  private final ClientTransportFactoryBuilder clientTransportFactoryBuilder;
  // TODO(sergiitk): see where getDefaultPort() not overridden, Might be in InProcess
  private final ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider;
  private int defaultPort;

  /**
   * Creates a new builder for the given target that will be resolved by
   * {@link io.grpc.NameResolver}.
   * TODO(sergiitk): finish javadoc
   */
  public ManagedChannelImplBuilder(String target,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    super(target);
    this.clientTransportFactoryBuilder = clientTransportFactoryBuilder;
    this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
//    this.defaultPort = GrpcUtil.DEFAULT_PORT_SSL;
  }

  //* TODO(sergiitk): finish javadoc */
  public ManagedChannelImplBuilder(SocketAddress directServerAddress, String authority,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    // TODO(sergiitk): setup checknotnull. see okhttp:391 Preconditions.checkArgument Preconditions.checknotnull
    super(directServerAddress, authority);
    this.clientTransportFactoryBuilder = clientTransportFactoryBuilder;
    this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
//    this.defaultPort = super.getDefaultPort();
  }

  @Override
  protected ClientTransportFactory buildTransportFactory() {
    if (channelBuilderDefaultPortProvider != null) {
      defaultPort = channelBuilderDefaultPortProvider.getDefaultPort();
    }
    return clientTransportFactoryBuilder.buildClientTransportFactory(maxInboundMessageSize());
  }

  @Override
  protected int getDefaultPort() {
    return channelBuilderDefaultPortProvider.getDefaultPort();
  }
}
