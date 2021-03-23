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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import io.envoyproxy.envoy.admin.v3.ClientResourceStatus;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump.DynamicListener;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump.DynamicListenerState;
import io.envoyproxy.envoy.admin.v3.RoutesConfigDump;
import io.envoyproxy.envoy.admin.v3.RoutesConfigDump.DynamicRouteConfig;
import io.envoyproxy.envoy.admin.v3.UpdateFailureState;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.status.v3.ClientConfig;
import io.envoyproxy.envoy.service.status.v3.ClientStatusDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.status.v3.ClientStatusRequest;
import io.envoyproxy.envoy.service.status.v3.ClientStatusResponse;
import io.envoyproxy.envoy.service.status.v3.PerXdsConfig;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.XdsClient.ResourceMetadata;
import java.util.EnumMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CsdsService}. */
@RunWith(Enclosed.class)
public class CsdsServiceTest {

  @RunWith(JUnit4.class)
  public static class ServiceTests {
    private static final String SERVER_NAME = InProcessServerBuilder.generateName();
    private static final String CONTROL_PLANE_URI = "trafficdirector.googleapis.com";
    private static final String NODE_ID =
        "projects/42/networks/default/nodes/5c85b298-6f5b-4722-b74a-f7d1f0ccf5ad";
    private static final ServerInfo BOOTSTRAP_SERVER =
        new ServerInfo(CONTROL_PLANE_URI, InsecureChannelCredentials.create(), true);
    private static final EnvoyProtoData.Node BOOTSTRAP_NODE =
        EnvoyProtoData.Node.newBuilder().setId(NODE_ID).build();
    private static final ClientStatusRequest REQUEST = ClientStatusRequest.newBuilder().build();

    @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
    private ClientStatusDiscoveryServiceGrpc.ClientStatusDiscoveryServiceBlockingStub blockingStub;
    private ObjectPool<XdsClient> xdsClientPool;
    private XdsClient xdsClient;

    @Before
    public void setUp() throws Exception {
      // Prepare XdsClient settings.
      Bootstrapper bootstrapper = new Bootstrapper() {
        @Override
        public BootstrapInfo bootstrap() {
          return new BootstrapInfo(ImmutableList.of(BOOTSTRAP_SERVER), BOOTSTRAP_NODE, null, null);
        }
      };
      SharedXdsClientPoolProvider xdsPoolProvider = new SharedXdsClientPoolProvider(bootstrapper);

      // Test server.
      cleanupRule.register(
          InProcessServerBuilder
              .forName(SERVER_NAME)
              .addService(new CsdsService(xdsPoolProvider))
              .directExecutor()
              .build()
              .start());

      // ??
      xdsClientPool = xdsPoolProvider.getXdsClientPool();
      xdsClient = xdsClientPool.getObject();

      // Test client.
      ManagedChannel channel = cleanupRule.register(
          InProcessChannelBuilder
              .forName(SERVER_NAME)
              .directExecutor()
              .build());
      blockingStub = ClientStatusDiscoveryServiceGrpc.newBlockingStub(channel);
    }

    @After
    public void tearDown() {
      if (xdsClient != null) {
        xdsClientPool.returnObject(xdsClient);
      }
    }

    @Test
    public void nodeInfo() {
      Node node = fetchClientConfig().getNode();
      assertThat(node.getId()).isEqualTo(NODE_ID);
      assertThat(node).isEqualTo(BOOTSTRAP_NODE.toEnvoyProtoNode());
    }

    @Test
    public void ldsConfig_empty() {
      ClientConfig config = fetchClientConfig();
      EnumMap<ResourceType, PerXdsConfig> configDumpMap = mapConfigDumps(config);
      assertThat(configDumpMap).containsKey(ResourceType.LDS);
      ListenersConfigDump ldsConfig = configDumpMap.get(ResourceType.LDS).getListenerConfig();
      assertThat(ldsConfig.getVersionInfo()).isEmpty();
      assertThat(ldsConfig.getStaticListenersCount()).isEqualTo(0);
      assertThat(ldsConfig.getDynamicListenersCount()).isEqualTo(0);
    }

    private EnumMap<ResourceType, PerXdsConfig> mapConfigDumps(ClientConfig config) {
      EnumMap<ResourceType, PerXdsConfig> xdsConfigMap = new EnumMap<>(ResourceType.class);
      for (PerXdsConfig perXdsConfig : config.getXdsConfigList()) {
        ResourceType type = perXdsConfigToResourceType(perXdsConfig);
        assertThat(type).isNotEqualTo(ResourceType.UNKNOWN);
        assertThat(xdsConfigMap).doesNotContainKey(type);
        xdsConfigMap.put(type, perXdsConfig);
      }
      return xdsConfigMap;
    }

    private ResourceType perXdsConfigToResourceType(PerXdsConfig perXdsConfig) {
      switch (perXdsConfig.getPerXdsConfigCase()) {
        case LISTENER_CONFIG:
          return ResourceType.LDS;
        case CLUSTER_CONFIG:
          return ResourceType.CDS;
        case ROUTE_CONFIG:
          return ResourceType.RDS;
        case ENDPOINT_CONFIG:
          return ResourceType.EDS;
        default:
          return ResourceType.UNKNOWN;
      }
    }

    private ClientConfig fetchClientConfig() {
      ClientStatusResponse response = blockingStub.fetchClientStatus(REQUEST);
      assertThat(response.getConfigCount()).isEqualTo(1);
      return response.getConfig(0);
    }
  }

  @RunWith(JUnit4.class)
  public static class MetadataToProtoTests {
    private static final String LDS_RESOURCE = "listener.googleapis.com";
    private static final String VERSION_1 = "42";
    private static final String VERSION_2 = "43";
    private static final String ERROR = "Parse error line 1\n Parse error line 2";
    private static final Any RAW_LISTENER = Any.pack(Listener.getDefaultInstance());
    private static final Any RAW_ROUTE_CONFIG = Any.pack(RouteConfiguration.getDefaultInstance());
    // Test timestamps.
    private static final Timestamp TIMESTAMP_ZERO = Timestamp.getDefaultInstance();
    private static final long NANOS_LAST_UPDATE = 1577923199_606042047L;
    private static final Timestamp TIMESTAMP_LAST_UPDATE = Timestamp.newBuilder()
        .setSeconds(1577923199L)  // 2020-01-01T23:59:59Z
        .setNanos(606042047)
        .build();
    private static final long NANOS_FAILED_UPDATE = 1609545599_732105843L;
    private static final Timestamp TIMESTAMP_FAILED_UPDATE = Timestamp.newBuilder()
        .setSeconds(1609545599L)  // 2021-01-01T23:59:59Z
        .setNanos(732105843)
        .build();

    /* LDS tests */

    @Test
    public void dumpLdsConfig() {
      Map<String, ResourceMetadata> resourcesMetadata = ImmutableMap.of(
          "A", ResourceMetadata.newResourceMetadataUnknown(),
          "B", ResourceMetadata.newResourceMetadataRequested());
      ListenersConfigDump ldsConfig = CsdsService.dumpLdsConfig(resourcesMetadata, VERSION_1);
      assertThat(ldsConfig.getVersionInfo()).isEqualTo(VERSION_1);
      assertThat(ldsConfig.getStaticListenersCount()).isEqualTo(0);
      assertThat(ldsConfig.getDynamicListenersCount()).isEqualTo(2);
      // Minimal check to confirm that resources generated from corresponding metadata.
      DynamicListener listenerA = ldsConfig.getDynamicListeners(0);
      assertThat(listenerA.getName()).isEqualTo("A");
      assertThat(listenerA.getClientStatus()).isEqualTo(ClientResourceStatus.UNKNOWN);
      DynamicListener listenerB = ldsConfig.getDynamicListeners(1);
      assertThat(listenerB.getName()).isEqualTo("B");
      assertThat(listenerB.getClientStatus()).isEqualTo(ClientResourceStatus.REQUESTED);
    }

    @Test
    public void buildDynamicListener_metadataUnknown() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataUnknown();
      DynamicListener dynamicListener = CsdsService.buildDynamicListener(LDS_RESOURCE, metadata);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.UNKNOWN, false);
      verifyDynamicListenerStateEmpty(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataDoesNotExist() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataDoesNotExist();
      DynamicListener dynamicListener = CsdsService.buildDynamicListener(LDS_RESOURCE, metadata);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.DOES_NOT_EXIST, false);
      verifyDynamicListenerStateEmpty(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataRequested() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataRequested();
      DynamicListener dynamicListener = CsdsService.buildDynamicListener(LDS_RESOURCE, metadata);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.REQUESTED, false);
      verifyDynamicListenerStateEmpty(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataAcked() {
      ResourceMetadata metadata =
          ResourceMetadata.newResourceMetadataAcked(RAW_LISTENER, VERSION_1, NANOS_LAST_UPDATE);
      DynamicListener dynamicListener = CsdsService.buildDynamicListener(LDS_RESOURCE, metadata);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.ACKED, false);
      verifyDynamicListenerStateNotEmpty(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataNackedFromRequested() {
      ResourceMetadata metadataRequested = ResourceMetadata.newResourceMetadataRequested();
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          metadataRequested, VERSION_2, NANOS_FAILED_UPDATE, ERROR);
      DynamicListener dynamicListener = CsdsService.buildDynamicListener(LDS_RESOURCE, metadata);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.NACKED, true);
      verifyErrorState(dynamicListener.getErrorState());
      verifyDynamicListenerStateEmpty(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataNackedFromAcked() {
      ResourceMetadata metadataAcked =
          ResourceMetadata.newResourceMetadataAcked(RAW_LISTENER, VERSION_1, NANOS_LAST_UPDATE);
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          metadataAcked, VERSION_2, NANOS_FAILED_UPDATE, ERROR);
      DynamicListener dynamicListener = CsdsService.buildDynamicListener(LDS_RESOURCE, metadata);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.NACKED, true);
      verifyErrorState(dynamicListener.getErrorState());
      verifyDynamicListenerStateNotEmpty(dynamicListener.getActiveState());
    }

    private void verifyDynamicListener(
        DynamicListener dynamicListener, ClientResourceStatus status, boolean hasErrorState) {
      assertWithMessage("name").that(dynamicListener.getName()).isEqualTo(LDS_RESOURCE);
      assertWithMessage("active_state").that(dynamicListener.hasActiveState()).isTrue();
      assertWithMessage("warming_state").that(dynamicListener.hasWarmingState()).isFalse();
      assertWithMessage("draining_state").that(dynamicListener.hasDrainingState()).isFalse();
      assertWithMessage("error_state").that(dynamicListener.hasErrorState())
          .isEqualTo(hasErrorState);
      assertWithMessage("client_status").that(dynamicListener.getClientStatus()).isEqualTo(status);
    }

    private void verifyDynamicListenerStateEmpty(DynamicListenerState dynamicListenerState) {
      assertWithMessage("version_info").that(dynamicListenerState.getVersionInfo()).isEmpty();
      assertWithMessage("listener").that(dynamicListenerState.hasListener()).isFalse();
      assertWithMessage("last_updated").that(dynamicListenerState.getLastUpdated())
          .isEqualTo(TIMESTAMP_ZERO);
    }

    private void verifyDynamicListenerStateNotEmpty(DynamicListenerState dynamicListenerState) {
      assertWithMessage("version_info").that(dynamicListenerState.getVersionInfo())
          .isEqualTo(VERSION_1);
      assertWithMessage("listener").that(dynamicListenerState.hasListener()).isTrue();
      assertWithMessage("listener").that(dynamicListenerState.getListener())
          .isEqualTo(RAW_LISTENER);
      assertWithMessage("last_updated").that(dynamicListenerState.getLastUpdated())
          .isEqualTo(TIMESTAMP_LAST_UPDATE);
    }

    /* RDS tests */

    @Test
    public void dumpRdsConfig() {
      Map<String, ResourceMetadata> resourcesMetadata = ImmutableMap.of(
          "A", ResourceMetadata.newResourceMetadataUnknown(),
          "B", ResourceMetadata.newResourceMetadataRequested());
      RoutesConfigDump rdsConfig = CsdsService.dumpRdsConfig(resourcesMetadata);
      assertThat(rdsConfig.getStaticRouteConfigsCount()).isEqualTo(0);
      assertThat(rdsConfig.getDynamicRouteConfigsCount()).isEqualTo(2);
      // Minimal check to confirm that resources generated from corresponding metadata.
      assertThat(rdsConfig.getDynamicRouteConfigs(0).getClientStatus())
          .isEqualTo(ClientResourceStatus.UNKNOWN);
      assertThat(rdsConfig.getDynamicRouteConfigs(1).getClientStatus())
          .isEqualTo(ClientResourceStatus.REQUESTED);
    }

    @Test
    public void buildDynamicRouteConfig_metadataUnknown() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataUnknown();
      DynamicRouteConfig dynamicRouteConfig = CsdsService.buildDynamicRouteConfig(metadata);
      verifyDynamicRouteConfigNoData(dynamicRouteConfig, ClientResourceStatus.UNKNOWN, false);
    }

    @Test
    public void buildDynamicRouteConfig_metadataDoesNotExist() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataDoesNotExist();
      DynamicRouteConfig dynamicRouteConfig = CsdsService.buildDynamicRouteConfig(metadata);
      verifyDynamicRouteConfigNoData(dynamicRouteConfig, ClientResourceStatus.DOES_NOT_EXIST,
          false);
    }

    @Test
    public void buildDynamicRouteConfig_metadataRequested() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataRequested();
      DynamicRouteConfig dynamicRouteConfig = CsdsService.buildDynamicRouteConfig(metadata);
      verifyDynamicRouteConfigNoData(dynamicRouteConfig, ClientResourceStatus.REQUESTED, false);
    }

    @Test
    public void buildDynamicRouteConfig_metadataAcked() {
      ResourceMetadata metadata =
          ResourceMetadata.newResourceMetadataAcked(RAW_ROUTE_CONFIG, VERSION_1, NANOS_LAST_UPDATE);
      DynamicRouteConfig dynamicRouteConfig = CsdsService.buildDynamicRouteConfig(metadata);
      verifyDynamicRouteConfigAccepted(dynamicRouteConfig, ClientResourceStatus.ACKED,
          RAW_ROUTE_CONFIG, false);
    }

    @Test
    public void buildDynamicRouteConfig_metadataNackedFromRequested() {
      ResourceMetadata metadataRequested = ResourceMetadata.newResourceMetadataRequested();
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          metadataRequested, VERSION_2, NANOS_FAILED_UPDATE, ERROR);
      DynamicRouteConfig dynamicRouteConfig = CsdsService.buildDynamicRouteConfig(metadata);
      verifyDynamicRouteConfigNoData(dynamicRouteConfig, ClientResourceStatus.NACKED, true);
      verifyErrorState(dynamicRouteConfig.getErrorState());
    }

    @Test
    public void buildDynamicRouteConfig_metadataNackedFromAcked() {
      ResourceMetadata metadataAcked =
          ResourceMetadata.newResourceMetadataAcked(RAW_ROUTE_CONFIG, VERSION_1, NANOS_LAST_UPDATE);
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          metadataAcked, VERSION_2, NANOS_FAILED_UPDATE, ERROR);
      DynamicRouteConfig dynamicRouteConfig = CsdsService.buildDynamicRouteConfig(metadata);
      verifyDynamicRouteConfigAccepted(dynamicRouteConfig, ClientResourceStatus.NACKED,
          RAW_ROUTE_CONFIG, true);
      verifyErrorState(dynamicRouteConfig.getErrorState());
    }

    private void verifyDynamicRouteConfigNoData(
        DynamicRouteConfig dynamicRouteConfig, ClientResourceStatus status, boolean hasErrorState) {
      assertWithMessage("version_info").that(dynamicRouteConfig.getVersionInfo()).isEmpty();
      assertWithMessage("route_config").that(dynamicRouteConfig.hasRouteConfig()).isFalse();
      assertWithMessage("last_updated").that(dynamicRouteConfig.getLastUpdated())
          .isEqualTo(TIMESTAMP_ZERO);
      assertWithMessage("error_state").that(dynamicRouteConfig.hasErrorState())
          .isEqualTo(hasErrorState);
      assertWithMessage("client_status").that(dynamicRouteConfig.getClientStatus())
          .isEqualTo(status);
    }

    private void verifyDynamicRouteConfigAccepted(
        DynamicRouteConfig dynamicRouteConfig, ClientResourceStatus status, Any rawResource,
        boolean hasErrorState) {
      assertWithMessage("version_info").that(dynamicRouteConfig.getVersionInfo())
          .isEqualTo(VERSION_1);
      assertWithMessage("route_config").that(dynamicRouteConfig.hasRouteConfig()).isTrue();
      assertWithMessage("route_config").that(dynamicRouteConfig.getRouteConfig())
          .isEqualTo(rawResource);
      assertWithMessage("last_updated").that(dynamicRouteConfig.getLastUpdated())
          .isEqualTo(TIMESTAMP_LAST_UPDATE);
      assertWithMessage("error_state").that(dynamicRouteConfig.hasErrorState())
          .isEqualTo(hasErrorState);
      assertWithMessage("client_status").that(dynamicRouteConfig.getClientStatus())
          .isEqualTo(status);
    }

    /* Common helpers */

    private void verifyErrorState(UpdateFailureState errorState) {
      assertWithMessage("failed_configuration").that(errorState.hasFailedConfiguration()).isFalse();
      assertWithMessage("last_update_attempt").that(errorState.getLastUpdateAttempt())
          .isEqualTo(TIMESTAMP_FAILED_UPDATE);
      assertWithMessage("details").that(errorState.getDetails()).isEqualTo(ERROR);
      assertWithMessage("version_info").that(errorState.getVersionInfo()).isEqualTo(VERSION_2);
    }
  }
}
