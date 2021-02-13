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
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CsdsService}. */
@RunWith(JUnit4.class)
public class CsdsServiceTest {

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private ManagedChannel channel;
  private ClientStatusDiscoveryServiceGrpc.ClientStatusDiscoveryServiceBlockingStub blockingStub;

  @Before
  public void setUp() throws Exception {
    final String serverName = InProcessServerBuilder.generateName();
    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .addService(createCsdsService())
            .directExecutor()
            .build()
            .start());
    channel = cleanupRule.register(
        InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build());
    blockingStub = ClientStatusDiscoveryServiceGrpc.newBlockingStub(channel);
  }

  private BindableService createCsdsService() {
    return new CsdsService();
  }

  @Test
  public void fetchClientStatus() {
    ClientStatusResponse response =
        blockingStub.fetchClientStatus(ClientStatusRequest.newBuilder().build());
    assertEquals(1, response.getConfigCount());
    ClientConfig config = response.getConfig(0);
    assertEquals("Hello world", config.getNode().getId());
  }
}
