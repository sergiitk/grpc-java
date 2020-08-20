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
import javax.annotation.Nullable;

public final class ManagedChannelImplBuilder
    extends AbstractManagedChannelImplBuilder<ManagedChannelImplBuilder> {

  private boolean authorityCheckerDisabled;
  @Deprecated @Nullable private OverrideAuthorityChecker authorityChecker;

  /**
   * TODO(sergiitk): finish javadoc.
   */
  public interface ClientTransportFactoryBuilder {
    ClientTransportFactory buildClientTransportFactory();
  }

  /**
   * TODO(sergiitk): finish javadoc.
   */
  public interface ChannelBuilderDefaultPortProvider {
    int getDefaultPort();
  }

  private class ManagedChannelDefaultPortProvider implements ChannelBuilderDefaultPortProvider {
    @Override
    public int getDefaultPort() {
      return ManagedChannelImplBuilder.super.getDefaultPort();
    }
  }

  private final ClientTransportFactoryBuilder clientTransportFactoryBuilder;
  private final ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider;

  /**
   * Creates a new builder for the given target that will be resolved by {@link
   * io.grpc.NameResolver}. TODO(sergiitk): finish javadoc
   */
  public ManagedChannelImplBuilder(String target,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    super(target);
    this.clientTransportFactoryBuilder = checkNotNull(clientTransportFactoryBuilder,
        "clientTransportFactoryBuilder cannot be null");

    if (channelBuilderDefaultPortProvider != null) {
      this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
    } else {
      this.channelBuilderDefaultPortProvider = new ManagedChannelDefaultPortProvider();
    }
  }

  /**
   * TODO(sergiitk): finish javadoc.
   */
  public ManagedChannelImplBuilder(SocketAddress directServerAddress, String authority,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    super(directServerAddress, authority);
    this.clientTransportFactoryBuilder = checkNotNull(clientTransportFactoryBuilder,
        "clientTransportFactoryBuilder cannot be null");

    if (channelBuilderDefaultPortProvider != null) {
      this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
    } else {
      this.channelBuilderDefaultPortProvider = new ManagedChannelDefaultPortProvider();
    }
  }

  @Override
  protected ClientTransportFactory buildTransportFactory() {
    return clientTransportFactoryBuilder.buildClientTransportFactory();
  }

  @Override
  protected int getDefaultPort() {
    return channelBuilderDefaultPortProvider.getDefaultPort();
  }

  /**
   * TODO(sergiitk): finish javadoc.
   */
  public ManagedChannelImplBuilder disableCheckAuthority() {
    authorityCheckerDisabled = true;
    return this;
  }

  /**
   * TODO(sergiitk): finish javadoc.
   */
  public ManagedChannelImplBuilder enableCheckAuthority() {
    authorityCheckerDisabled = false;
    return this;
  }

  @Deprecated
  public interface OverrideAuthorityChecker {
    String checkAuthority(String authority);
  }

  @Deprecated
  public void overrideAuthorityChecker(@Nullable OverrideAuthorityChecker authorityChecker) {
    this.authorityChecker = authorityChecker;
  }

  @Override
  protected String checkAuthority(String authority) {
    if (authorityCheckerDisabled) {
      return authority;
    }
    if (authorityChecker != null) {
      return authorityChecker.checkAuthority(authority);
    }
    return super.checkAuthority(authority);
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
  public void setTracingEnabled(boolean value) {
    super.setTracingEnabled(value);
  }

  @Override
  public ObjectPool<? extends Executor> getOffloadExecutorPool() {
    return super.getOffloadExecutorPool();
  }
}
