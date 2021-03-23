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

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump;
import io.envoyproxy.envoy.config.core.v3.Node;
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
import java.util.EnumMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CsdsService}. */
@RunWith(JUnit4.class)
public class CsdsServiceTest {
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
      @Override public BootstrapInfo bootstrap() {
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
