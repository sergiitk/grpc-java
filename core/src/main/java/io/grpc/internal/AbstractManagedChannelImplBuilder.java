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
import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.BinaryLog;
import io.grpc.ClientInterceptor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalChannelz;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.NameResolverRegistry;
import io.grpc.ProxyDetector;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Abstract base class for channel builders.
 *
 * @param <T> The concrete type of this builder.
 */
public abstract class AbstractManagedChannelImplBuilder
        <T extends AbstractManagedChannelImplBuilder<T>> extends ManagedChannelBuilder<T> {
  private static final String DIRECT_ADDRESS_SCHEME = "directaddress";

  private static final Logger log =
      Logger.getLogger(AbstractManagedChannelImplBuilder.class.getName());

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

  final String target;

  @Nullable
  protected final SocketAddress directServerAddress;

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

  protected TransportTracer.Factory transportTracerFactory = TransportTracer.getDefaultFactory();

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

  protected AbstractManagedChannelImplBuilder(String target) {
    this.target = Preconditions.checkNotNull(target, "target");
    this.directServerAddress = null;
  }

  /**
   * Returns a target string for the SocketAddress. It is only used as a placeholder, because
   * DirectAddressNameResolverFactory will not actually try to use it. However, it must be a valid
   * URI.
   */
  @VisibleForTesting
  static String makeTargetStringForDirectAddress(SocketAddress address) {
    try {
      return new URI(DIRECT_ADDRESS_SCHEME, "", "/" + address, null).toString();
    } catch (URISyntaxException e) {
      // It should not happen.
      throw new RuntimeException(e);
    }
  }

  protected AbstractManagedChannelImplBuilder(SocketAddress directServerAddress, String authority) {
    this.target = makeTargetStringForDirectAddress(directServerAddress);
    this.directServerAddress = directServerAddress;
    this.nameResolverFactory = new DirectAddressNameResolverFactory(directServerAddress, authority);
  }

  // Temporarily disable retry when stats or tracing is enabled to avoid breakage, until we know
  // what should be the desired behavior for retry + stats/tracing.
  // TODO(zdapeng): FIX IT
  @VisibleForTesting
  final List<ClientInterceptor> getEffectiveInterceptors() {
    List<ClientInterceptor> effectiveInterceptors =
        new ArrayList<>(this.interceptors);
    temporarilyDisableRetry = false;
    if (statsEnabled) {
      temporarilyDisableRetry = true;
      ClientInterceptor statsInterceptor = null;
      try {
        Class<?> censusStatsAccessor =
            Class.forName("io.grpc.census.InternalCensusStatsAccessor");
        Method getClientInterceptorMethod =
            censusStatsAccessor.getDeclaredMethod(
                "getClientInterceptor",
                boolean.class,
                boolean.class,
                boolean.class);
        statsInterceptor =
            (ClientInterceptor) getClientInterceptorMethod
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
      if (statsInterceptor != null) {
        // First interceptor runs last (see ClientInterceptors.intercept()), so that no
        // other interceptor can override the tracer factory we set in CallOptions.
        effectiveInterceptors.add(0, statsInterceptor);
      }
    }
    if (tracingEnabled) {
      temporarilyDisableRetry = true;
      ClientInterceptor tracingInterceptor = null;
      try {
        Class<?> censusTracingAccessor =
            Class.forName("io.grpc.census.InternalCensusTracingAccessor");
        Method getClientInterceptroMethod =
            censusTracingAccessor.getDeclaredMethod("getClientInterceptor");
        tracingInterceptor = (ClientInterceptor) getClientInterceptroMethod.invoke(null);
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
      if (tracingInterceptor != null) {
        effectiveInterceptors.add(0, tracingInterceptor);
      }
    }
    return effectiveInterceptors;
  }

  /**
   * Subclasses should override this method to provide the {@link ClientTransportFactory}
   * appropriate for this channel. This method is meant for Transport implementors and should not
   * be used by normal users.
   */
  protected abstract ClientTransportFactory buildTransportFactory();

  /**
   * Subclasses can override this method to provide a default port to {@link NameResolver} for use
   * in cases where the target string doesn't include a port.  The default implementation returns
   * {@link GrpcUtil#DEFAULT_PORT_SSL}.
   */
  protected int getDefaultPort() {
    return GrpcUtil.DEFAULT_PORT_SSL;
  }

  /**
   * Returns a {@link NameResolver.Factory} for the channel.
   */
  NameResolver.Factory getNameResolverFactory() {
    if (authorityOverride == null) {
      return nameResolverFactory;
    } else {
      return new OverrideAuthorityNameResolverFactory(nameResolverFactory, authorityOverride);
    }
  }

  private static class DirectAddressNameResolverFactory extends NameResolver.Factory {
    final SocketAddress address;
    final String authority;

    DirectAddressNameResolverFactory(SocketAddress address, String authority) {
      this.address = address;
      this.authority = authority;
    }

    @Override
    public NameResolver newNameResolver(URI notUsedUri, NameResolver.Args args) {
      return new NameResolver() {
        @Override
        public String getServiceAuthority() {
          return authority;
        }

        @Override
        public void start(Listener2 listener) {
          listener.onResult(
              ResolutionResult.newBuilder()
                  .setAddresses(Collections.singletonList(new EquivalentAddressGroup(address)))
                  .setAttributes(Attributes.EMPTY)
                  .build());
        }

        @Override
        public void shutdown() {}
      };
    }

    @Override
    public String getDefaultScheme() {
      return DIRECT_ADDRESS_SCHEME;
    }
  }

  /**
   * Returns the internal offload executor pool for offloading tasks.
   */
  protected ObjectPool<? extends Executor> getOffloadExecutorPool() {
    return this.offloadExecutorPool;
  }
}
