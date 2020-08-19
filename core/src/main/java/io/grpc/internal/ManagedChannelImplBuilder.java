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

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.SocketAddress;
import java.util.concurrent.Executor;

// TODO(sergiitk): We won't be able to override checkAuthority anymore - bury it here
public final class ManagedChannelImplBuilder
    extends AbstractManagedChannelImplBuilder<ManagedChannelImplBuilder> {

  private boolean authorityCheckerDisabled;

  /** TODO(sergiitk): finish javadoc */
  public interface ClientTransportFactoryBuilder {
    ClientTransportFactory buildClientTransportFactory();
  }

  /** TODO(sergiitk): finish javadoc */
  public interface ChannelBuilderDefaultPortProvider {
    int getDefaultPort();
  }

  private final ClientTransportFactoryBuilder clientTransportFactoryBuilder;
  // TODO(sergiitk): see where getDefaultPort() not overridden, Might be in InProcess
  private final ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider;

  /**
   * Creates a new builder for the given target that will be resolved by
   * {@link io.grpc.NameResolver}.
   * TODO(sergiitk): finish javadoc
   */
  public ManagedChannelImplBuilder(String target,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    super(target);
    this.clientTransportFactoryBuilder = checkNotNull(clientTransportFactoryBuilder,
        "clientTransportFactoryBuilder cannot be null");
    this.channelBuilderDefaultPortProvider = checkNotNull(channelBuilderDefaultPortProvider,
        "channelBuilderDefaultPortProvider cannot be null");
  }

  /** TODO(sergiitk): finish javadoc */
  public ManagedChannelImplBuilder(SocketAddress directServerAddress, String authority,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    super(directServerAddress, authority);
    this.clientTransportFactoryBuilder = checkNotNull(clientTransportFactoryBuilder,
        "clientTransportFactoryBuilder cannot be null");
    this.channelBuilderDefaultPortProvider = checkNotNull(channelBuilderDefaultPortProvider,
        "channelBuilderDefaultPortProvider cannot be null");
  }

  /** TODO(sergiitk): finish javadoc */
  public ManagedChannelImplBuilder disableCheckAuthority() {
    authorityCheckerDisabled = true;
    return this;
  }

  /** TODO(sergiitk): finish javadoc */
  public ManagedChannelImplBuilder enableCheckAuthority() {
    authorityCheckerDisabled = false;
    return this;
  }

  @Override
  public ObjectPool<? extends Executor> getOffloadExecutorPool() {
    return super.getOffloadExecutorPool();
  }

  @Override
  protected String checkAuthority(String authority) {
    return authorityCheckerDisabled ? authority : super.checkAuthority(authority);
  }

  @Override
  protected ClientTransportFactory buildTransportFactory() {
    return clientTransportFactoryBuilder.buildClientTransportFactory();
  }

  @Override
  protected int getDefaultPort() {
    return channelBuilderDefaultPortProvider.getDefaultPort();
  }
}
