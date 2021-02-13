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

import static org.junit.Assert.assertEquals;

import io.envoyproxy.envoy.service.status.v3.ClientConfig;
import io.envoyproxy.envoy.service.status.v3.ClientStatusDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.status.v3.ClientStatusRequest;
import io.envoyproxy.envoy.service.status.v3.ClientStatusResponse;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.Node;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CsdsService}. */
@RunWith(JUnit4.class)
public class CsdsServiceTest {
  private static final String CONTROL_PLANE_URI = "trafficdirector.googleapis.com";
  private static final String NODE_ID =
      "projects/42/networks/default/nodes/5c85b298-6f5b-4722-b74a-f7d1f0ccf5ad";

  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private final Node node = Node.newBuilder().setId(NODE_ID).build();
  private final ServerInfo controlPlane =
      new ServerInfo(CONTROL_PLANE_URI, InsecureChannelCredentials.create(), true);

  private ClientStatusDiscoveryServiceGrpc.ClientStatusDiscoveryServiceBlockingStub blockingStub;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;

  @Before
  public void setUp() throws Exception {
    // Prepare XdsClient settings.
    Bootstrapper bootstrapper = new Bootstrapper() {
      @Override public BootstrapInfo bootstrap() {
        return new BootstrapInfo(Collections.singletonList(controlPlane), node, null, null);
      }
    };
    SharedXdsClientPoolProvider xdsPoolProvider = new SharedXdsClientPoolProvider(bootstrapper);

    // Test server.
    final String serverName = InProcessServerBuilder.generateName();
    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .addService(new CsdsService(xdsPoolProvider))
            .directExecutor()
            .build()
            .start());

    xdsClientPool = xdsPoolProvider.getXdsClientPool();
    xdsClient = xdsClientPool.getObject();

    // Test client.
    ManagedChannel channel = cleanupRule.register(
        InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build());
    blockingStub = ClientStatusDiscoveryServiceGrpc.newBlockingStub(channel);
  }

  @After
  public void tearDown() throws Exception {
    if (xdsClient != null) {
      xdsClientPool.returnObject(xdsClient);
    }
  }

  @Test
  public void fetchClientStatus() {
    ClientStatusResponse response =
        blockingStub.fetchClientStatus(ClientStatusRequest.newBuilder().build());
    assertEquals(1, response.getConfigCount());
    ClientConfig config = response.getConfig(0);
    assertEquals(NODE_ID, config.getNode().getId());
  }
}
