/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.extensions.filters.http.ratelimit.v3.RateLimit;
import io.envoyproxy.envoy.extensions.filters.http.ratelimit.v3.RateLimitPerRoute;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import io.grpc.xds.RateLimitPerRouteConfig.VhRateLimitsOptions;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** Rate Limit HTTP filter implementation. */
final class RateLimitFilter implements Filter, ServerInterceptorBuilder {
  private static final Logger logger = Logger.getLogger(RateLimitFilter.class.getName());

  static final RateLimitFilter INSTANCE = new RateLimitFilter();

  static final String TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.http.ratelimit.v3.RateLimit";

  static final String TYPE_URL_OVERRIDE_CONFIG =
      "type.googleapis.com/envoy.extensions.filters.http.ratelimit.v3.RateLimitPerRoute";

  @Override
  public String[] typeUrls() {
    return new String[] { TYPE_URL, TYPE_URL_OVERRIDE_CONFIG };
  }

  @Override
  public ConfigOrError<RateLimitConfig> parseFilterConfig(Message rawProtoMessage) {
    try {
      return parseRateLimitConfig(unpackConfigMessage(rawProtoMessage, RateLimit.class));
    } catch (InvalidProtocolBufferException e) {
      return ConfigOrError.fromError("Can't unpack RateLimit config: " + e);
    }
  }

  @VisibleForTesting
  static ConfigOrError<RateLimitConfig> parseRateLimitConfig(RateLimit rateLimit) {
    String domain = rateLimit.getDomain();
    // TODO(sergiitk): parse all fields
    return ConfigOrError.fromConfig(RateLimitConfig.create(domain));
  }

  @Override
  public ConfigOrError<RateLimitPerRouteConfig> parseFilterConfigOverride(Message rawProtoMessage) {
    try {
      return parseRateLimitPerRouteConfig(
          unpackConfigMessage(rawProtoMessage, RateLimitPerRoute.class));
    } catch (InvalidProtocolBufferException e) {
      return ConfigOrError.fromError("Can't unpack RateLimit config: " + e);
    }
  }

  @VisibleForTesting
  static ConfigOrError<RateLimitPerRouteConfig> parseRateLimitPerRouteConfig(
      RateLimitPerRoute rateLimitPerRoute) {
    RateLimitPerRoute.VhRateLimitsOptions vhRateLimits = rateLimitPerRoute.getVhRateLimits();
    switch (vhRateLimits) {
      case OVERRIDE:
        return ConfigOrError
            .fromConfig(RateLimitPerRouteConfig.create(VhRateLimitsOptions.OVERRIDE));
      case INCLUDE:
        return ConfigOrError
            .fromConfig(RateLimitPerRouteConfig.create(VhRateLimitsOptions.INCLUDE));
      case IGNORE:
        return ConfigOrError
            .fromConfig(RateLimitPerRouteConfig.create(VhRateLimitsOptions.IGNORE));
      case UNRECOGNIZED:
      default:
        return ConfigOrError
            .fromError("Unexpected VH Rate Limits Option: " + vhRateLimits);
    }
  }

  @Nullable
  @Override
  public ServerInterceptor buildServerInterceptor(
      final FilterConfig config, @Nullable FilterConfig overrideConfig) {
    checkNotNull(config, "config");
    RateLimitConfig rateLimitConfig = (RateLimitConfig) config;

    if (overrideConfig != null) {
      VhRateLimitsOptions vhRateLimits = ((RateLimitPerRouteConfig) overrideConfig).vhRateLimits();
      if (vhRateLimits == VhRateLimitsOptions.IGNORE) {
        // Ignore the virtual host rate limits even if the route does not have a rate limit policy.
        return null;
      }
      // TODO(sergiitk): how to override with route config?
    }

    return buildRateLimitInterceptor(rateLimitConfig);
  }

  private ServerInterceptor buildRateLimitInterceptor(final RateLimitConfig rateLimitConfig) {
    checkNotNull(rateLimitConfig, "rateLimitConfig");
    // map rateLimitConfig.domain() -> object
    // shared resource holder, acquire every rpc
    // this is per route configuration
    // store RLS Client or channel in the config as a reference - FilterConfig config ref when parse
    //   - atomic maybe
    //   - allocate channel on demand / ref counting
    //   - and interface to notify service interceptor on shutdown
    //   - destroy channel when ref count 0
    // potentially many RLS Clients sharing a channel to grpc RLS service -
    //   TODO(sergiitk): look up how cache is looked up
    // now we create filters every RPC. will be change in RBAC.
    //    we need to avoid recreating filter when config doesn't change
    //    m: trigger close() after we create new instances
    //    RBAC filter recreate? - has to be fixed for RBAC
    // TODO(sergiitk): buffering calls. See DelayedClientCall. We need this for server.
    //   we need to be careful with draining buffered calls from the queue to avoid races.
    //   We need the same thing as described in https://github.com/grpc/grpc-java/issues/7868 on serv side.
    // AI: https://github.com/grpc/grpc-java/issues/7868 discuss on stabl meeting
    // AI: follow up with Eric on how cache is shared, this changes if we need to cache interceptor

    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          final ServerCall<ReqT, RespT> call,
          final Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        // TODO(sergiitk): FINE log
        logger.log(Level.INFO,
            "Rate Limit interceptor for serverCall {0} headers {1}: domain {2}, ...TODO.",
            new Object[]{call, headers, rateLimitConfig.domain()});

        // TODO(sergiitk): process request based on options.
        return next.startCall(call, headers);
      }
    };
  }

  private static <T extends com.google.protobuf.Message> T unpackConfigMessage(
      Message message, Class<T> clazz) throws InvalidProtocolBufferException {
    if (!(message instanceof Any)) {
      throw new InvalidProtocolBufferException("Invalid config type: " + message.getClass());
    }
    return ((Any) message).unpack(clazz);
  }
}

