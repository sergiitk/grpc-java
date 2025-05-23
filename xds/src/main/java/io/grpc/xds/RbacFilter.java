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

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.core.v3.CidrRange;
import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC;
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBACPerRoute;
import io.envoyproxy.envoy.type.v3.Int32Range;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.xds.internal.MatcherParser;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AlwaysTrueMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AndMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthConfig;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthDecision;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthHeaderMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthenticatedMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.DestinationIpMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.DestinationPortMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.DestinationPortRangeMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.InvertMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.Matcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.OrMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.PathMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.PolicyMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.RequestedServerNameMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.SourceIpMatcher;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** RBAC Http filter implementation. */
final class RbacFilter implements Filter {
  private static final Logger logger = Logger.getLogger(RbacFilter.class.getName());

  private static final RbacFilter INSTANCE = new RbacFilter();

  static final String TYPE_URL =
          "type.googleapis.com/envoy.extensions.filters.http.rbac.v3.RBAC";

  private static final String TYPE_URL_OVERRIDE_CONFIG =
          "type.googleapis.com/envoy.extensions.filters.http.rbac.v3.RBACPerRoute";

  private RbacFilter() {}

  static final class Provider implements Filter.Provider {
    @Override
    public String[] typeUrls() {
      return new String[] {TYPE_URL, TYPE_URL_OVERRIDE_CONFIG};
    }

    @Override
    public boolean isServerFilter() {
      return true;
    }

    @Override
    public RbacFilter newInstance(String name) {
      return INSTANCE;
    }

    @Override
    public ConfigOrError<RbacConfig> parseFilterConfig(Message rawProtoMessage) {
      RBAC rbacProto;
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      Any anyMessage = (Any) rawProtoMessage;
      try {
        rbacProto = anyMessage.unpack(RBAC.class);
      } catch (InvalidProtocolBufferException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }
      return parseRbacConfig(rbacProto);
    }

    @Override
    public ConfigOrError<RbacConfig> parseFilterConfigOverride(Message rawProtoMessage) {
      RBACPerRoute rbacPerRoute;
      if (!(rawProtoMessage instanceof Any)) {
        return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
      }
      Any anyMessage = (Any) rawProtoMessage;
      try {
        rbacPerRoute = anyMessage.unpack(RBACPerRoute.class);
      } catch (InvalidProtocolBufferException e) {
        return ConfigOrError.fromError("Invalid proto: " + e);
      }
      if (rbacPerRoute.hasRbac()) {
        return parseRbacConfig(rbacPerRoute.getRbac());
      } else {
        return ConfigOrError.fromConfig(RbacConfig.create(null));
      }
    }

    static ConfigOrError<RbacConfig> parseRbacConfig(RBAC rbac) {
      if (!rbac.hasRules()) {
        return ConfigOrError.fromConfig(RbacConfig.create(null));
      }
      io.envoyproxy.envoy.config.rbac.v3.RBAC rbacConfig = rbac.getRules();
      GrpcAuthorizationEngine.Action authAction;
      switch (rbacConfig.getAction()) {
        case ALLOW:
          authAction = GrpcAuthorizationEngine.Action.ALLOW;
          break;
        case DENY:
          authAction = GrpcAuthorizationEngine.Action.DENY;
          break;
        case LOG:
          return ConfigOrError.fromConfig(RbacConfig.create(null));
        case UNRECOGNIZED:
        default:
          return ConfigOrError.fromError(
              "Unknown rbacConfig action type: " + rbacConfig.getAction());
      }
      List<GrpcAuthorizationEngine.PolicyMatcher> policyMatchers = new ArrayList<>();
      List<Entry<String, Policy>> sortedPolicyEntries = rbacConfig.getPoliciesMap().entrySet()
          .stream()
          .sorted((a,b) -> a.getKey().compareTo(b.getKey()))
          .collect(Collectors.toList());
      for (Map.Entry<String, Policy> entry: sortedPolicyEntries) {
        try {
          Policy policy = entry.getValue();
          if (policy.hasCondition() || policy.hasCheckedCondition()) {
            return ConfigOrError.fromError(
                "Policy.condition and Policy.checked_condition must not set: " + entry.getKey());
          }
          policyMatchers.add(PolicyMatcher.create(entry.getKey(),
              parsePermissionList(policy.getPermissionsList()),
              parsePrincipalList(policy.getPrincipalsList())));
        } catch (Exception e) {
          return ConfigOrError.fromError("Encountered error parsing policy: " + e);
        }
      }
      return ConfigOrError.fromConfig(RbacConfig.create(
          AuthConfig.create(policyMatchers, authAction)));
    }
  }

  @Nullable
  @Override
  public ServerInterceptor buildServerInterceptor(FilterConfig config,
                                                  @Nullable FilterConfig overrideConfig) {
    checkNotNull(config, "config");
    if (overrideConfig != null) {
      config = overrideConfig;
    }
    AuthConfig authConfig = ((RbacConfig) config).authConfig();
    return authConfig == null ? null : generateAuthorizationInterceptor(authConfig);
  }

  private ServerInterceptor generateAuthorizationInterceptor(AuthConfig config) {
    checkNotNull(config, "config");
    final GrpcAuthorizationEngine authEngine = new GrpcAuthorizationEngine(config);
    return new ServerInterceptor() {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                final ServerCall<ReqT, RespT> call,
                final Metadata headers, ServerCallHandler<ReqT, RespT> next) {
          AuthDecision authResult = authEngine.evaluate(headers, call);
          if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE,
                "Authorization result for serverCall {0}: {1}, matching policy: {2}.",
                new Object[]{call, authResult.decision(), authResult.matchingPolicyName()});
          }
          if (GrpcAuthorizationEngine.Action.DENY.equals(authResult.decision())) {
            Status status = Status.PERMISSION_DENIED.withDescription("Access Denied");
            call.close(status, new Metadata());
            return new ServerCall.Listener<ReqT>(){};
          }
          return next.startCall(call, headers);
        }
    };
  }

  private static OrMatcher parsePermissionList(List<Permission> permissions) {
    List<Matcher> anyMatch = new ArrayList<>();
    for (Permission permission : permissions) {
      anyMatch.add(parsePermission(permission));
    }
    return OrMatcher.create(anyMatch);
  }

  private static Matcher parsePermission(Permission permission) {
    switch (permission.getRuleCase()) {
      case AND_RULES:
        List<Matcher> andMatch = new ArrayList<>();
        for (Permission p : permission.getAndRules().getRulesList()) {
          andMatch.add(parsePermission(p));
        }
        return AndMatcher.create(andMatch);
      case OR_RULES:
        return parsePermissionList(permission.getOrRules().getRulesList());
      case ANY:
        return AlwaysTrueMatcher.INSTANCE;
      case HEADER:
        return parseHeaderMatcher(permission.getHeader());
      case URL_PATH:
        return parsePathMatcher(permission.getUrlPath());
      case DESTINATION_IP:
        return createDestinationIpMatcher(permission.getDestinationIp());
      case DESTINATION_PORT:
        return createDestinationPortMatcher(permission.getDestinationPort());
      case DESTINATION_PORT_RANGE:
        return parseDestinationPortRangeMatcher(permission.getDestinationPortRange());
      case NOT_RULE:
        return InvertMatcher.create(parsePermission(permission.getNotRule()));
      case METADATA: // hard coded, never match.
        return InvertMatcher.create(AlwaysTrueMatcher.INSTANCE);
      case REQUESTED_SERVER_NAME:
        return parseRequestedServerNameMatcher(permission.getRequestedServerName());
      case RULE_NOT_SET:
      default:
        throw new IllegalArgumentException(
                "Unknown permission rule case: " + permission.getRuleCase());
    }
  }

  private static OrMatcher parsePrincipalList(List<Principal> principals) {
    List<Matcher> anyMatch = new ArrayList<>();
    for (Principal principal: principals) {
      anyMatch.add(parsePrincipal(principal));
    }
    return OrMatcher.create(anyMatch);
  }

  private static Matcher parsePrincipal(Principal principal) {
    switch (principal.getIdentifierCase()) {
      case OR_IDS:
        return parsePrincipalList(principal.getOrIds().getIdsList());
      case AND_IDS:
        List<Matcher> nextMatchers = new ArrayList<>();
        for (Principal next : principal.getAndIds().getIdsList()) {
          nextMatchers.add(parsePrincipal(next));
        }
        return AndMatcher.create(nextMatchers);
      case ANY:
        return AlwaysTrueMatcher.INSTANCE;
      case AUTHENTICATED:
        return parseAuthenticatedMatcher(principal.getAuthenticated());
      case DIRECT_REMOTE_IP:
        return createSourceIpMatcher(principal.getDirectRemoteIp());
      case REMOTE_IP:
        return createSourceIpMatcher(principal.getRemoteIp());
      case SOURCE_IP: {
        // gRFC A41 has identical handling of source_ip as remote_ip and direct_remote_ip and
        // pre-dates the deprecation.
        @SuppressWarnings("deprecation")
        CidrRange sourceIp = principal.getSourceIp();
        return createSourceIpMatcher(sourceIp);
      }
      case HEADER:
        return parseHeaderMatcher(principal.getHeader());
      case NOT_ID:
        return InvertMatcher.create(parsePrincipal(principal.getNotId()));
      case URL_PATH:
        return parsePathMatcher(principal.getUrlPath());
      case METADATA: // hard coded, never match.
        return InvertMatcher.create(AlwaysTrueMatcher.INSTANCE);
      case IDENTIFIER_NOT_SET:
      default:
        throw new IllegalArgumentException(
                "Unknown principal identifier case: " + principal.getIdentifierCase());
    }
  }

  private static PathMatcher parsePathMatcher(
          io.envoyproxy.envoy.type.matcher.v3.PathMatcher proto) {
    switch (proto.getRuleCase()) {
      case PATH:
        return PathMatcher.create(MatcherParser.parseStringMatcher(proto.getPath()));
      case RULE_NOT_SET:
      default:
        throw new IllegalArgumentException(
                "Unknown path matcher rule type: " + proto.getRuleCase());
    }
  }

  private static RequestedServerNameMatcher parseRequestedServerNameMatcher(
          io.envoyproxy.envoy.type.matcher.v3.StringMatcher proto) {
    return RequestedServerNameMatcher.create(MatcherParser.parseStringMatcher(proto));
  }

  private static AuthHeaderMatcher parseHeaderMatcher(
          io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto) {
    if (proto.getName().startsWith("grpc-")) {
      throw new IllegalArgumentException("Invalid header matcher config: [grpc-] prefixed "
          + "header name is not allowed.");
    }
    if (":scheme".equals(proto.getName())) {
      throw new IllegalArgumentException("Invalid header matcher config: header name [:scheme] "
          + "is not allowed.");
    }
    return AuthHeaderMatcher.create(MatcherParser.parseHeaderMatcher(proto));
  }

  private static AuthenticatedMatcher parseAuthenticatedMatcher(
          Principal.Authenticated proto) {
    Matchers.StringMatcher matcher = MatcherParser.parseStringMatcher(proto.getPrincipalName());
    return AuthenticatedMatcher.create(matcher);
  }

  private static DestinationPortMatcher createDestinationPortMatcher(int port) {
    return DestinationPortMatcher.create(port);
  }

  private static DestinationPortRangeMatcher parseDestinationPortRangeMatcher(Int32Range range) {
    return DestinationPortRangeMatcher.create(range.getStart(), range.getEnd());
  }

  private static DestinationIpMatcher createDestinationIpMatcher(CidrRange cidrRange) {
    return DestinationIpMatcher.create(Matchers.CidrMatcher.create(
            resolve(cidrRange), cidrRange.getPrefixLen().getValue()));
  }

  private static SourceIpMatcher createSourceIpMatcher(CidrRange cidrRange) {
    return SourceIpMatcher.create(Matchers.CidrMatcher.create(
            resolve(cidrRange), cidrRange.getPrefixLen().getValue()));
  }

  private static InetAddress resolve(CidrRange cidrRange) {
    try {
      return InetAddress.getByName(cidrRange.getAddressPrefix());
    } catch (UnknownHostException ex) {
      throw new IllegalArgumentException("IP address can not be found: " + ex);
    }
  }
}

