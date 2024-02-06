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

import com.google.auto.value.AutoValue;
import io.grpc.xds.Filter.FilterConfig;

/**
 * Rate Limit Per Route HTTP filter configuration. See
 * {@link io.envoyproxy.envoy.extensions.filters.http.ratelimit.v3.RateLimitPerRoute}.
 */
@AutoValue
abstract class RateLimitPerRouteConfig implements FilterConfig {
  enum VhRateLimitsOptions {
    OVERRIDE,
    INCLUDE,
    IGNORE,
  }

  static RateLimitPerRouteConfig create(VhRateLimitsOptions vhRateLimits) {
    return new AutoValue_RateLimitPerRouteConfig(vhRateLimits);
  }

  abstract VhRateLimitsOptions vhRateLimits();

  @Override
  public final String typeUrl() {
    return RateLimitFilter.TYPE_URL_OVERRIDE_CONFIG;
  }
}
