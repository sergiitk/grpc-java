/*
 * Copyright 2019 The gRPC Authors
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
import static io.grpc.xds.AbstractXdsClient.ResourceType.LDS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.FakeClock.TaskFilter;
import io.grpc.internal.TimeProvider;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.ClientXdsClient.ResourceMetadata;
import io.grpc.xds.ClientXdsClient.ResourceMetadata.ResourceMetadataStatus;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.HttpFault.FractionalPercent.DenominatorType;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.XdsClient.CdsResourceWatcher;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.XdsClient.CdsUpdate.ClusterType;
import io.grpc.xds.XdsClient.CdsUpdate.HashFunction;
import io.grpc.xds.XdsClient.EdsResourceWatcher;
import io.grpc.xds.XdsClient.EdsUpdate;
import io.grpc.xds.XdsClient.LdsResourceWatcher;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.XdsClient.RdsResourceWatcher;
import io.grpc.xds.XdsClient.RdsUpdate;
import io.grpc.xds.XdsClient.ResourceWatcher;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Tests for {@link ClientXdsClient}.
 */
@RunWith(JUnit4.class)
public abstract class ClientXdsClientTestBase {
  private static final String LDS_RESOURCE = "listener.googleapis.com";
  private static final String RDS_RESOURCE = "route-configuration.googleapis.com";
  private static final String CDS_RESOURCE = "cluster.googleapis.com";
  private static final String EDS_RESOURCE = "cluster-load-assignment.googleapis.com";
  private static final String VERSION_1 = "42";
  private static final String VERSION_2 = "43";
  private static final long ONE_SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final Node NODE = Node.newBuilder().build();

  private static final FakeClock.TaskFilter RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(AbstractXdsClient.RpcRetryTask.class.getSimpleName());
        }
      };

  private static final FakeClock.TaskFilter LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(LDS.toString());
        }
      };

  private static final FakeClock.TaskFilter RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.RDS.toString());
        }
      };

  private static final FakeClock.TaskFilter CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.CDS.toString());
        }
      };

  private static final FakeClock.TaskFilter EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.EDS.toString());
        }
      };

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private final FakeClock fakeClock = new FakeClock();
  protected final Queue<DiscoveryRpcCall> resourceDiscoveryCalls = new ArrayDeque<>();
  protected final Queue<LrsRpcCall> loadReportCalls = new ArrayDeque<>();
  protected final AtomicBoolean adsEnded = new AtomicBoolean(true);
  protected final AtomicBoolean lrsEnded = new AtomicBoolean(true);
  private final MessageFactory mf = createMessageFactory();
  private final Any listenerVhosts = Any.pack(mf.buildListener(LDS_RESOURCE,
      mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(2))));
  private final Any listenerRds = Any.pack(mf.buildListenerForRds(LDS_RESOURCE, RDS_RESOURCE));

  @Captor
  private ArgumentCaptor<LdsUpdate> ldsUpdateCaptor;
  @Captor
  private ArgumentCaptor<RdsUpdate> rdsUpdateCaptor;
  @Captor
  private ArgumentCaptor<CdsUpdate> cdsUpdateCaptor;
  @Captor
  private ArgumentCaptor<EdsUpdate> edsUpdateCaptor;
  @Captor
  private ArgumentCaptor<Status> errorCaptor;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;
  @Mock
  private LdsResourceWatcher ldsResourceWatcher;
  @Mock
  private RdsResourceWatcher rdsResourceWatcher;
  @Mock
  private CdsResourceWatcher cdsResourceWatcher;
  @Mock
  private EdsResourceWatcher edsResourceWatcher;
  @Mock
  private TimeProvider timeProvider;

  private ManagedChannel channel;
  private ClientXdsClient xdsClient;
  private boolean originalEnableFaultInjection;

  @Before
  public void setUp() throws IOException {
    originalEnableFaultInjection = ClientXdsClient.enableFaultInjection;
    ClientXdsClient.enableFaultInjection = true;
    MockitoAnnotations.initMocks(this);
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos()).thenReturn(10L, 100L);
    when(backoffPolicy2.nextBackoffNanos()).thenReturn(20L, 200L);
    // Increment time 1 second each call.
    when(timeProvider.currentTimeNanos()).thenAnswer(new Answer<Long>() {
      private long seconds = 0;
      @Override
      public Long answer(InvocationOnMock invocation) {
        return TimeUnit.SECONDS.toNanos(++seconds);
      }
    });

    final String serverName = InProcessServerBuilder.generateName();
    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .addService(createAdsService())
            .addService(createLrsService())
            .directExecutor()
            .build()
            .start());
    channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());

    xdsClient =
        new ClientXdsClient(
            channel,
            useProtocolV3(),
            EnvoyProtoData.Node.newBuilder().build(),
            fakeClock.getScheduledExecutorService(),
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier(),
            timeProvider);

    assertThat(resourceDiscoveryCalls).isEmpty();
    assertThat(loadReportCalls).isEmpty();
  }

  @After
  public void tearDown() {
    ClientXdsClient.enableFaultInjection = originalEnableFaultInjection;
    xdsClient.shutdown();
    channel.shutdown();  // channel not owned by XdsClient
    assertThat(adsEnded.get()).isTrue();
    assertThat(lrsEnded.get()).isTrue();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  protected abstract boolean useProtocolV3();

  protected abstract BindableService createAdsService();

  protected abstract BindableService createLrsService();

  protected abstract MessageFactory createMessageFactory();

  private void verifySubscribedResourcesMetadataSizes(
      int ldsSize, int cdsSize, int rdsSize, int edsSize) {
    assertThat(xdsClient.getSubscribedResourcesMetadata(LDS)).hasSize(ldsSize);
    assertThat(xdsClient.getSubscribedResourcesMetadata(ResourceType.CDS)).hasSize(cdsSize);
    assertThat(xdsClient.getSubscribedResourcesMetadata(ResourceType.RDS)).hasSize(rdsSize);
    assertThat(xdsClient.getSubscribedResourcesMetadata(ResourceType.EDS)).hasSize(edsSize);
  }

  /** Verify the resource requested, but not updated. */
  private void verifyResourceMetadataRequested(ResourceType type, String resourceName) {
    ResourceMetadata resourceMetadata = xdsClient.getSubscribedResourceMetadata(type, resourceName);
    assertThat(resourceMetadata).isNotNull();
    assertThat(resourceMetadata.getStatus()).isEqualTo(ResourceMetadataStatus.REQUESTED);
    assertThat(resourceMetadata.getVersion()).isEmpty();
    assertThat(resourceMetadata.getUpdateTime()).isEqualTo(0);
    assertThat(resourceMetadata.getRawResource()).isNull();
  }

  /** Verify the resource was acked. */
  private void verifyResourceMetadataAcked(
      ResourceType type, String resourceName, Any listener, String versionInfo, long updateTime) {
    ResourceMetadata resourceMetadata = xdsClient.getSubscribedResourceMetadata(type, resourceName);
    assertThat(resourceMetadata).isNotNull();
    assertThat(resourceMetadata.getVersion()).isEqualTo(versionInfo);
    assertThat(resourceMetadata.getRawResource()).isEqualTo(listener);
    assertThat(resourceMetadata.getUpdateTime()).isEqualTo(updateTime);
  }

  @Test
  public void ldsResourceNotFound() {
    DiscoveryRpcCall call = startResourceWatcher(LDS, LDS_RESOURCE, ldsResourceWatcher);

    Any listener = Any.pack(mf.buildListener("bar.googleapis.com",
        mf.buildRouteConfiguration("route-bar.googleapis.com", mf.buildOpaqueVirtualHosts(1))));
    call.sendResponse(LDS, listener, VERSION_1, "0000");

    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verifyNoInteractions(ldsResourceWatcher);
    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);
  }

  @Test
  public void ldsResourceFound_containsVirtualHosts() {
    DiscoveryRpcCall call = startResourceWatcher(LDS, LDS_RESOURCE, ldsResourceWatcher);
    fakeClock.forwardTime(10, TimeUnit.SECONDS);

    // Client sends an ACK LDS request.
    call.sendResponse(LDS, listenerVhosts, VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().virtualHosts).hasSize(2);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, listenerVhosts, VERSION_1, ONE_SECOND_NANOS);
  }

  @Test
  public void ldsResourceFound_containsRdsName() {
    DiscoveryRpcCall call = startResourceWatcher(LDS, LDS_RESOURCE, ldsResourceWatcher);
    call.sendResponse(LDS, listenerRds, VERSION_1, "0000");

    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().rdsName).isEqualTo(RDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, listenerRds, VERSION_1, ONE_SECOND_NANOS);
  }

  @Test
  public void cachedLdsResource_data() {
    DiscoveryRpcCall call = startResourceWatcher(LDS, LDS_RESOURCE, ldsResourceWatcher);

    // Client sends an ACK LDS request.
    call.sendResponse(LDS, listenerRds, VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    // Add another watcher.
    LdsResourceWatcher watcher = mock(LdsResourceWatcher.class);
    xdsClient.watchLdsResource(LDS_RESOURCE, watcher);
    // Verify both watchers were called.
    verify(watcher).onChanged(ldsUpdateCaptor.capture());
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().rdsName).isEqualTo(RDS_RESOURCE);
    call.verifyNoMoreRequest();
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, listenerRds, VERSION_1, ONE_SECOND_NANOS);
  }

  @Test
  public void cachedLdsResource_absent() {
    DiscoveryRpcCall call = startResourceWatcher(LDS, LDS_RESOURCE, ldsResourceWatcher);
    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    LdsResourceWatcher watcher = mock(LdsResourceWatcher.class);
    xdsClient.watchLdsResource(LDS_RESOURCE, watcher);
    // Verify neither watcher was called.
    verify(watcher).onResourceDoesNotExist(LDS_RESOURCE);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    call.verifyNoMoreRequest();
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);
  }

  @Test
  public void ldsResourceUpdated() {
    DiscoveryRpcCall call = startResourceWatcher(LDS, LDS_RESOURCE, ldsResourceWatcher);

    // Initial LDS response.
    call.sendResponse(LDS, listenerVhosts, VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().virtualHosts).hasSize(2);

    // Updated LDS response.
    call.sendResponse(LDS, listenerRds, VERSION_2, "0001");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_2, "0001", NODE);
    verify(ldsResourceWatcher, times(2)).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().rdsName).isEqualTo(RDS_RESOURCE);
  }

  @Test
  public void ldsResourceUpdate_withFaultInjection() {
    Assume.assumeTrue(useProtocolV3());
    DiscoveryRpcCall call =
        startResourceWatcher(LDS, LDS_RESOURCE, ldsResourceWatcher);
    List<Any> listeners = ImmutableList.of(
        Any.pack(mf.buildListener(
            LDS_RESOURCE,
            mf.buildRouteConfiguration(
                "do not care",
                ImmutableList.of(
                    mf.buildVirtualHost(
                        mf.buildOpaqueRoutes(1),
                        ImmutableMap.of(
                            "irrelevant",
                            Any.pack(StringValue.of("irrelevant")),
                            "envoy.fault",
                            mf.buildHttpFaultTypedConfig(
                                300L, 1000, "cluster1", ImmutableList.<String>of(), 100, null, null,
                                null))),
                    mf.buildVirtualHost(
                        mf.buildOpaqueRoutes(2),
                        ImmutableMap.of(
                            "envoy.fault",
                            mf.buildHttpFaultTypedConfig(
                                null, null, "cluster2", ImmutableList.<String>of(), 101, null, 503,
                                2000)))
                )),
            ImmutableList.of(
                mf.buildHttpFilter("irrelevant", null),
                mf.buildHttpFilter("envoy.fault", null)
            ))));
    call.sendResponse(LDS, listeners, VERSION_1, "0000");

    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, Collections.singletonList(LDS_RESOURCE), VERSION_1, "0000", NODE
    );
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    LdsUpdate ldsUpdate = ldsUpdateCaptor.getValue();
    assertThat(ldsUpdate.virtualHosts).hasSize(2);
    assertThat(ldsUpdate.hasFaultInjection).isTrue();
    assertThat(ldsUpdate.httpFault).isNull();
    HttpFault httpFault = ldsUpdate.virtualHosts.get(0).httpFault();
    assertThat(httpFault.faultDelay().delayNanos()).isEqualTo(300);
    assertThat(httpFault.faultDelay().percent().numerator()).isEqualTo(1000);
    assertThat(httpFault.faultDelay().percent().denominatorType())
        .isEqualTo(DenominatorType.MILLION);
    assertThat(httpFault.faultAbort()).isNull();
    assertThat(httpFault.upstreamCluster()).isEqualTo("cluster1");
    assertThat(httpFault.maxActiveFaults()).isEqualTo(100);
    httpFault = ldsUpdate.virtualHosts.get(1).httpFault();
    assertThat(httpFault.faultDelay()).isNull();
    assertThat(httpFault.faultAbort().status().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(httpFault.faultAbort().percent().numerator()).isEqualTo(2000);
    assertThat(httpFault.faultAbort().percent().denominatorType())
        .isEqualTo(DenominatorType.MILLION);
    assertThat(httpFault.upstreamCluster()).isEqualTo("cluster2");
    assertThat(httpFault.maxActiveFaults()).isEqualTo(101);
  }

  @Test
  public void ldsResourceDeleted() {
    DiscoveryRpcCall call =
        startResourceWatcher(LDS, LDS_RESOURCE, ldsResourceWatcher);
    List<Any> listeners = ImmutableList.of(
        Any.pack(mf.buildListener(LDS_RESOURCE,
            mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(2)))));
    call.sendResponse(LDS, listeners, VERSION_1, "0000");

    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, Collections.singletonList(LDS_RESOURCE), VERSION_1, "0000", NODE
    );
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().virtualHosts).hasSize(2);

    call.sendResponse(LDS, Collections.<Any>emptyList(), VERSION_2, "0001");

    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, Collections.singletonList(LDS_RESOURCE), VERSION_2, "0001", NODE
    );
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
  }

  @Test
  public void multipleLdsWatchers() {
    String ldsResource = "bar.googleapis.com";
    LdsResourceWatcher watcher1 = mock(LdsResourceWatcher.class);
    LdsResourceWatcher watcher2 = mock(LdsResourceWatcher.class);
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchLdsResource(ldsResource, watcher1);
    xdsClient.watchLdsResource(ldsResource, watcher2);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, Arrays.asList(LDS_RESOURCE, ldsResource), "", "", NODE);

    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(ldsResource);
    verify(watcher2).onResourceDoesNotExist(ldsResource);

    List<Any> listeners = ImmutableList.of(
        Any.pack(mf.buildListener(LDS_RESOURCE,
            mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(2)))),
        Any.pack(mf.buildListener(ldsResource,
            mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(4)))));
    call.sendResponse(LDS, listeners, VERSION_1, "0000");
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().virtualHosts).hasSize(2);
    verify(watcher1).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().virtualHosts).hasSize(4);
    verify(watcher2).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().virtualHosts).hasSize(4);
  }

  @Test
  public void rdsResourceNotFound() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration("route-bar.googleapis.com",
            mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(ResourceType.RDS, routeConfigs, VERSION_1, "0000");

    // Client sends an ACK RDS request.
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), VERSION_1, "0000",
        NODE
    );

    verifyNoInteractions(rdsResourceWatcher);
    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void rdsResourceFound() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(ResourceType.RDS, routeConfigs, VERSION_1, "0000");

    // Client sends an ACK RDS request.
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(2);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void cachedRdsResource_data() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(ResourceType.RDS, routeConfigs, VERSION_1, "0000");

    // Client sends an ACK RDS request.
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), VERSION_1, "0000",
        NODE
    );

    RdsResourceWatcher watcher = mock(RdsResourceWatcher.class);
    xdsClient.watchRdsResource(RDS_RESOURCE, watcher);
    verify(watcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(2);
    call.verifyNoMoreRequest();
  }

  @Test
  public void cachedRdsResource_absent() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
    RdsResourceWatcher watcher = mock(RdsResourceWatcher.class);
    xdsClient.watchRdsResource(RDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(RDS_RESOURCE);
    call.verifyNoMoreRequest();
  }

  @Test
  public void rdsResourceUpdated() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(ResourceType.RDS, routeConfigs, VERSION_1, "0000");

    // Client sends an ACK RDS request.
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(2);

    routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(4))));
    call.sendResponse(ResourceType.RDS, routeConfigs, VERSION_2, "0001");

    // Client sends an ACK RDS request.
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), VERSION_2, "0001",
        NODE
    );
    verify(rdsResourceWatcher, times(2)).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(4);
  }

  @Test
  public void rdsResourceDeletedByLds() {
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    List<Any> listeners = ImmutableList.of(
        Any.pack(mf.buildListenerForRds(LDS_RESOURCE, RDS_RESOURCE)));
    call.sendResponse(LDS, listeners, VERSION_1, "0000");
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().rdsName).isEqualTo(RDS_RESOURCE);

    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(ResourceType.RDS, routeConfigs, VERSION_1, "0000");
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(2);

    listeners = ImmutableList.of(
        Any.pack(mf.buildListener(LDS_RESOURCE,
            mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(5)))));
    call.sendResponse(LDS, listeners, VERSION_2, "0001");
    verify(ldsResourceWatcher, times(2)).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().virtualHosts).hasSize(5);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
  }

  @Test
  public void multipleRdsWatchers() {
    String rdsResource = "route-bar.googleapis.com";
    RdsResourceWatcher watcher1 = mock(RdsResourceWatcher.class);
    RdsResourceWatcher watcher2 = mock(RdsResourceWatcher.class);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    xdsClient.watchRdsResource(rdsResource, watcher1);
    xdsClient.watchRdsResource(rdsResource, watcher2);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(ResourceType.RDS, Arrays.asList(RDS_RESOURCE, rdsResource), "", "", NODE);

    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(rdsResource);
    verify(watcher2).onResourceDoesNotExist(rdsResource);

    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(ResourceType.RDS, routeConfigs, VERSION_1, "0000");

    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(2);
    verifyNoMoreInteractions(watcher1, watcher2);

    routeConfigs = ImmutableList.of(Any.pack(
        mf.buildRouteConfiguration(rdsResource, mf.buildOpaqueVirtualHosts(4))));
    call.sendResponse(ResourceType.RDS, routeConfigs, "2", "0002");

    verify(watcher1).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(4);
    verify(watcher2).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(4);
    verifyNoMoreInteractions(rdsResourceWatcher);
  }

  @Test
  public void cdsResourceNotFound() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);

    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster("cluster-bar.googleapis.com", null, "round_robin", null,
            false, null, null)),
        Any.pack(mf.buildEdsCluster("cluster-baz.googleapis.com", null, "round_robin", null,
            false, null, null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verifyNoInteractions(cdsResourceWatcher);

    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void cdsResourceFound() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, null, "round_robin", null, false, null, null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isNull();
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerName()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void cdsResourceFound_ringHashLbPolicy() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    Message ringHashConfig = mf.buildRingHashLbConfig("xx_hash", 10L, 100L);
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, null, "ring_hash", ringHashConfig, false, null,
            null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isNull();
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("ring_hash");
    assertThat(cdsUpdate.hashFunction()).isEqualTo(HashFunction.XX_HASH);
    assertThat(cdsUpdate.minRingSize()).isEqualTo(10L);
    assertThat(cdsUpdate.maxRingSize()).isEqualTo(100L);
    assertThat(cdsUpdate.lrsServerName()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void cdsResponseWithAggregateCluster() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<String> candidates = Arrays.asList(
        "cluster1.googleapis.com", "cluster2.googleapis.com", "cluster3.googleapis.com");
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildAggregateCluster(CDS_RESOURCE, "round_robin", null, candidates)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.AGGREGATE);
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.prioritizedClusterNames()).containsExactlyElementsIn(candidates).inOrder();
  }

  @Test
  public void cdsResponseWithCircuitBreakers() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, null, "round_robin", null, false, null,
            mf.buildCircuitBreakers(50, 200))));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isNull();
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerName()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isEqualTo(200L);
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
  }

  /**
   * CDS response containing UpstreamTlsContext for a cluster.
   */
  @Test
  public void cdsResponseWithUpstreamTlsContext() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);

    // Management server sends back CDS response with UpstreamTlsContext.
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildLogicalDnsCluster("cluster-bar.googleapis.com", "round_robin", null,
            false, null, null)),
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, "eds-cluster-foo.googleapis.com", "round_robin",
            null, true,
            mf.buildUpstreamTlsContext("secret1", "unix:/var/uds2"), null)),
        Any.pack(mf.buildEdsCluster("cluster-baz.googleapis.com", null, "round_robin", null, false,
            null, null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(cdsResourceWatcher, times(1)).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    SdsSecretConfig validationContextSdsSecretConfig =
        cdsUpdate.upstreamTlsContext().getCommonTlsContext().getValidationContextSdsSecretConfig();
    assertThat(validationContextSdsSecretConfig.getName()).isEqualTo("secret1");
    assertThat(
        Iterables.getOnlyElement(
            validationContextSdsSecretConfig
                .getSdsConfig()
                .getApiConfigSource()
                .getGrpcServicesList())
            .getGoogleGrpc()
            .getTargetUri())
        .isEqualTo("unix:/var/uds2");
  }

  @Test
  public void cachedCdsResource_data() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, null, "round_robin", null, false, null, null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");

    // Client sends an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_1, "0000",
        NODE
    );

    CdsResourceWatcher watcher = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource(CDS_RESOURCE, watcher);
    verify(watcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isNull();
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerName()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    call.verifyNoMoreRequest();
  }

  @Test
  public void cachedCdsResource_absent() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    CdsResourceWatcher watcher = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource(CDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(CDS_RESOURCE);
    call.verifyNoMoreRequest();
  }

  @Test
  public void cdsResourceUpdated() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildLogicalDnsCluster(CDS_RESOURCE, "round_robin", null, false, null, null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");

    // Client sends an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.LOGICAL_DNS);
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerName()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();

    String edsService = "eds-service-bar.googleapis.com";
    clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, edsService, "round_robin", null, true, null,
            null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_2, "0001");

    // Client sends an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_2, "0001",
        NODE
    );
    verify(cdsResourceWatcher, times(2)).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(edsService);
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerName()).isEqualTo("");
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
  }

  @Test
  public void cdsResourceDeleted() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, null, "round_robin", null, false, null, null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");

    // Client sends an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isNull();
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerName()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();

    call.sendResponse(ResourceType.CDS, Collections.<Any>emptyList(), VERSION_2, "0001");

    // Client sends an ACK CDS request.
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), VERSION_2, "0001",
        NODE
    );
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
  }

  @Test
  public void multipleCdsWatchers() {
    String cdsResource = "cluster-bar.googleapis.com";
    CdsResourceWatcher watcher1 = mock(CdsResourceWatcher.class);
    CdsResourceWatcher watcher2 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchCdsResource(cdsResource, watcher1);
    xdsClient.watchCdsResource(cdsResource, watcher2);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(ResourceType.CDS, Arrays.asList(CDS_RESOURCE, cdsResource), "", "", NODE);

    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(cdsResource);
    verify(watcher2).onResourceDoesNotExist(cdsResource);

    String edsService = "eds-service-bar.googleapis.com";
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildLogicalDnsCluster(CDS_RESOURCE, "round_robin", null, false, null, null)),
        Any.pack(mf.buildEdsCluster(cdsResource, edsService, "round_robin", null, true, null,
            null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.LOGICAL_DNS);
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerName()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    verify(watcher1).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(cdsResource);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(edsService);
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerName()).isEqualTo("");
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    verify(watcher2).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(cdsResource);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(edsService);
    assertThat(cdsUpdate.lbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerName()).isEqualTo("");
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
  }

  @Test
  public void edsResourceNotFound() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                mf.buildClusterLoadAssignment("cluster-bar.googleapis.com",
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.0.1", 8080, "healthy", 2)),
                            1, 0)),
                    ImmutableList.<Message>of())));
    call.sendResponse(ResourceType.EDS, clusterLoadAssignments, VERSION_1, "0000");

    // Client sent an ACK EDS request.
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verifyNoInteractions(edsResourceWatcher);

    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void edsResourceFound() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                mf.buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.0.1", 8080, "healthy", 2)),
                            1, 0),
                        mf.buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                            ImmutableList.<Message>of(),
                            2, 1), /* locality with 0 endpoint */
                        mf.buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.142.5", 80, "unknown", 5)),
                            0, 2) /* locality with 0 weight */),
                    ImmutableList.of(
                        mf.buildDropOverload("lb", 200),
                        mf.buildDropOverload("throttle", 1000)))));
    call.sendResponse(ResourceType.EDS, clusterLoadAssignments, VERSION_1, "0000");

    // Client sent an ACK EDS request.
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.dropPolicies)
        .containsExactly(
            DropOverload.create("lb", 200),
            DropOverload.create("throttle", 1000));
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region1", "zone1", "subzone1"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("192.168.0.1", 8080,
                        2, true)), 1, 0),
            Locality.create("region3", "zone3", "subzone3"),
            LocalityLbEndpoints.create(ImmutableList.<LbEndpoint>of(), 2, 1));
  }

  @Test
  public void cachedEdsResource_data() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                mf.buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.0.1", 8080, "healthy", 2)),
                            1, 0),
                        mf.buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                            ImmutableList.<Message>of(),
                            2, 1), /* locality with 0 endpoint */
                        mf.buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.142.5", 80, "unknown", 5)),
                            0, 2) /* locality with 0 weight */),
                    ImmutableList.of(
                        mf.buildDropOverload("lb", 200),
                        mf.buildDropOverload("throttle", 1000)))));
    call.sendResponse(ResourceType.EDS, clusterLoadAssignments, VERSION_1, "0000");

    // Client sent an ACK EDS request.
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), VERSION_1, "0000",
        NODE
    );

    EdsResourceWatcher watcher = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource(EDS_RESOURCE, watcher);
    verify(watcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.dropPolicies)
        .containsExactly(
            DropOverload.create("lb", 200),
            DropOverload.create("throttle", 1000));
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region1", "zone1", "subzone1"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("192.168.0.1", 8080,
                        2, true)), 1, 0),
            Locality.create("region3", "zone3", "subzone3"),
            LocalityLbEndpoints.create(ImmutableList.<LbEndpoint>of(), 2, 1));
    call.verifyNoMoreRequest();
  }

  @Test
  public void cachedEdsResource_absent() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    EdsResourceWatcher watcher = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource(EDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(EDS_RESOURCE);
    call.verifyNoMoreRequest();
  }

  @Test
  public void edsResourceUpdated() {
    DiscoveryRpcCall call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                mf.buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.0.1", 8080, "healthy", 2)),
                            1, 0),
                        mf.buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                            ImmutableList.<Message>of(),
                            2, 1), /* locality with 0 endpoint */
                        mf.buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.142.5", 80, "unknown", 5)),
                            0, 2) /* locality with 0 weight */),
                    ImmutableList.of(
                        mf.buildDropOverload("lb", 200),
                        mf.buildDropOverload("throttle", 1000)))));
    call.sendResponse(ResourceType.EDS, clusterLoadAssignments, VERSION_1, "0000");

    // Client sent an ACK EDS request.
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), VERSION_1, "0000",
        NODE
    );
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.dropPolicies)
        .containsExactly(
            DropOverload.create("lb", 200),
            DropOverload.create("throttle", 1000));
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region1", "zone1", "subzone1"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("192.168.0.1", 8080, 2, true)), 1, 0),
            Locality.create("region3", "zone3", "subzone3"),
            LocalityLbEndpoints.create(ImmutableList.<LbEndpoint>of(), 2, 1));

    clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                mf.buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                            ImmutableList.of(
                                mf.buildLbEndpoint("172.44.2.2", 8000, "unknown", 3)),
                            2, 0)),
                    ImmutableList.<Message>of())));
    call.sendResponse(ResourceType.EDS, clusterLoadAssignments, VERSION_2, "0001");

    verify(edsResourceWatcher, times(2)).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.dropPolicies).isEmpty();
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region2", "zone2", "subzone2"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("172.44.2.2", 8000, 3, true)), 2, 0));
  }

  @Test
  public void edsResourceDeletedByCds() {
    String resource = "backend-service.googleapis.com";
    CdsResourceWatcher cdsWatcher = mock(CdsResourceWatcher.class);
    EdsResourceWatcher edsWatcher = mock(EdsResourceWatcher.class);
    xdsClient.watchCdsResource(resource, cdsWatcher);
    xdsClient.watchEdsResource(resource, edsWatcher);
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(resource, null, "round_robin", null, true, null, null)),
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, EDS_RESOURCE, "round_robin", null, false, null,
            null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_1, "0000");
    verify(cdsWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(null);
    assertThat(cdsUpdate.lrsServerName()).isEqualTo("");
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(EDS_RESOURCE);
    assertThat(cdsUpdate.lrsServerName()).isNull();

    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                mf.buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.0.1", 8080, "healthy", 2)),
                            1, 0)),
                    ImmutableList.of(
                        mf.buildDropOverload("lb", 200),
                        mf.buildDropOverload("throttle", 1000)))),
            Any.pack(
                mf.buildClusterLoadAssignment(resource,
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.0.2", 9090, "healthy", 3)),
                            1, 0)),
                    ImmutableList.of(
                        mf.buildDropOverload("lb", 100)))));
    call.sendResponse(ResourceType.EDS, clusterLoadAssignments, VERSION_1, "0000");
    verify(edsWatcher).onChanged(edsUpdateCaptor.capture());
    assertThat(edsUpdateCaptor.getValue().clusterName).isEqualTo(resource);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    assertThat(edsUpdateCaptor.getValue().clusterName).isEqualTo(EDS_RESOURCE);

    clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(resource, null, "round_robin", null, true, null,
            null)),  // no change
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, null, "round_robin", null, false, null, null)));
    call.sendResponse(ResourceType.CDS, clusters, VERSION_2, "0001");
    verify(cdsResourceWatcher, times(2)).onChanged(cdsUpdateCaptor.capture());
    assertThat(cdsUpdateCaptor.getValue().edsServiceName()).isNull();
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    verifyNoMoreInteractions(cdsWatcher, edsWatcher);
  }

  @Test
  public void multipleEdsWatchers() {
    String edsResource = "cluster-load-assignment-bar.googleapis.com";
    EdsResourceWatcher watcher1 = mock(EdsResourceWatcher.class);
    EdsResourceWatcher watcher2 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    xdsClient.watchEdsResource(edsResource, watcher1);
    xdsClient.watchEdsResource(edsResource, watcher2);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(ResourceType.EDS, Arrays.asList(EDS_RESOURCE, edsResource), "", "", NODE);

    fakeClock.forwardTime(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(edsResource);
    verify(watcher2).onResourceDoesNotExist(edsResource);

    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                mf.buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.0.1", 8080, "healthy", 2)),
                            1, 0),
                        mf.buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                            ImmutableList.<Message>of(),
                            2, 1), /* locality with 0 endpoint */
                        mf.buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                            ImmutableList.of(
                                mf.buildLbEndpoint("192.168.142.5", 80, "unknown", 5)),
                            0, 2) /* locality with 0 weight */),
                    ImmutableList.of(
                        mf.buildDropOverload("lb", 200),
                        mf.buildDropOverload("throttle", 1000)))));
    call.sendResponse(ResourceType.EDS, clusterLoadAssignments, VERSION_1, "0000");
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.dropPolicies)
        .containsExactly(
            DropOverload.create("lb", 200),
            DropOverload.create("throttle", 1000));
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region1", "zone1", "subzone1"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("192.168.0.1", 8080, 2, true)), 1, 0),
            Locality.create("region3", "zone3", "subzone3"),
            LocalityLbEndpoints.create(ImmutableList.<LbEndpoint>of(), 2, 1));
    verifyNoMoreInteractions(watcher1, watcher2);

    clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                mf.buildClusterLoadAssignment(edsResource,
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                            ImmutableList.of(
                                mf.buildLbEndpoint("172.44.2.2", 8000, "healthy", 3)),
                            2, 0)),
                    ImmutableList.<Message>of())));
    call.sendResponse(ResourceType.EDS, clusterLoadAssignments, VERSION_2, "0001");

    verify(watcher1).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(edsResource);
    assertThat(edsUpdate.dropPolicies).isEmpty();
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region2", "zone2", "subzone2"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("172.44.2.2", 8000, 3, true)), 2, 0));
    verify(watcher2).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(edsResource);
    assertThat(edsUpdate.dropPolicies).isEmpty();
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region2", "zone2", "subzone2"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("172.44.2.2", 8000, 3, true)), 2, 0));
    verifyNoMoreInteractions(edsResourceWatcher);
  }

  @Test
  public void streamClosedAndRetryWithBackoff() {
    InOrder inOrder = Mockito.inOrder(backoffPolicyProvider, backoffPolicy1, backoffPolicy2);
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, Collections.singletonList(LDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), "", "", NODE);

    // Management server closes the RPC stream with an error.
    call.sendError(Status.UNKNOWN.asException());
    verify(ldsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);
    verify(rdsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);
    verify(cdsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);
    verify(edsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);

    // Retry after backoff.
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    ScheduledTask retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(10L);
    fakeClock.forwardNanos(10L);
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, Collections.singletonList(LDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), "", "", NODE);

    // Management server becomes unreachable.
    call.sendError(Status.UNAVAILABLE.asException());
    verify(ldsResourceWatcher, times(2)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(rdsResourceWatcher, times(2)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(cdsResourceWatcher, times(2)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(edsResourceWatcher, times(2)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);

    // Retry after backoff.
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(100L);
    fakeClock.forwardNanos(100L);
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, Collections.singletonList(LDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), "", "", NODE);

    List<Any> listeners = ImmutableList.of(
        Any.pack(mf.buildListener(LDS_RESOURCE,
            mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(2)))));
    call.sendResponse(LDS, listeners, "63", "3242");
    call.verifyRequest(LDS, Collections.singletonList(LDS_RESOURCE), "63", "3242", NODE
    );

    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(ResourceType.RDS, routeConfigs, "5", "6764");
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), "5", "6764", NODE
    );

    call.sendError(Status.DEADLINE_EXCEEDED.asException());
    verify(ldsResourceWatcher, times(3)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
    verify(rdsResourceWatcher, times(3)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
    verify(cdsResourceWatcher, times(3)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
    verify(edsResourceWatcher, times(3)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);

    // Reset backoff sequence and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, Collections.singletonList(LDS_RESOURCE), "63", "", NODE);
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), "5", "", NODE);
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), "", "", NODE);

    // Management server becomes unreachable again.
    call.sendError(Status.UNAVAILABLE.asException());
    verify(ldsResourceWatcher, times(4)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(rdsResourceWatcher, times(4)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(cdsResourceWatcher, times(4)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(edsResourceWatcher, times(4)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);

    // Retry after backoff.
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(20L);
    fakeClock.forwardNanos(20L);
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, Collections.singletonList(LDS_RESOURCE), "63", "", NODE);
    call.verifyRequest(ResourceType.RDS, Collections.singletonList(RDS_RESOURCE), "5", "", NODE);
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), "", "", NODE);

    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void streamClosedAndRetryRaceWithAddRemoveWatchers() {
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.sendError(Status.UNAVAILABLE.asException());
    verify(ldsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(rdsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    ScheduledTask retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(10L);

    xdsClient.cancelLdsResourceWatch(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.cancelRdsResourceWatch(RDS_RESOURCE, rdsResourceWatcher);
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    fakeClock.forwardNanos(10L);
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(ResourceType.CDS, Collections.singletonList(CDS_RESOURCE), "", "", NODE);
    call.verifyRequest(ResourceType.EDS, Collections.singletonList(EDS_RESOURCE), "", "", NODE);
    call.verifyNoMoreRequest();

    List<Any> listeners = ImmutableList.of(
        Any.pack(mf.buildListener(LDS_RESOURCE,
            mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(2)))));
    call.sendResponse(LDS, listeners, VERSION_1, "0000");
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(ResourceType.RDS, routeConfigs, VERSION_1, "0000");

    verifyNoMoreInteractions(ldsResourceWatcher, rdsResourceWatcher);
  }

  @Test
  public void streamClosedAndRetryRestartsResourceInitialFetchTimerForUnresolvedResources() {
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    ScheduledTask ldsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    ScheduledTask rdsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    ScheduledTask cdsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    ScheduledTask edsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    List<Any> listeners = ImmutableList.of(
        Any.pack(mf.buildListener(LDS_RESOURCE, mf.buildRouteConfiguration("do not care",
            mf.buildOpaqueVirtualHosts(2)))));
    call.sendResponse(LDS, listeners, VERSION_1, "0000");
    assertThat(ldsResourceTimeout.isCancelled()).isTrue();

    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(ResourceType.RDS, routeConfigs, VERSION_1, "0000");
    assertThat(rdsResourceTimeout.isCancelled()).isTrue();

    call.sendError(Status.UNAVAILABLE.asException());
    assertThat(cdsResourceTimeout.isCancelled()).isTrue();
    assertThat(edsResourceTimeout.isCancelled()).isTrue();

    fakeClock.forwardNanos(10L);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(0);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(0);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
  }

  @Test
  public void reportLoadStatsToServer() {
    String clusterName = "cluster-foo.googleapis.com";
    ClusterDropStats dropStats = xdsClient.addClusterDropStats(clusterName, null);
    LrsRpcCall lrsCall = loadReportCalls.poll();
    lrsCall.verifyNextReportClusters(Collections.<String[]>emptyList()); // initial LRS request

    lrsCall.sendResponse(Collections.singletonList(clusterName), 1000L);
    fakeClock.forwardNanos(1000L);
    lrsCall.verifyNextReportClusters(Collections.singletonList(new String[] {clusterName, null}));

    dropStats.release();
    fakeClock.forwardNanos(1000L);
    // In case of having unreported cluster stats, one last report will be sent after corresponding
    // stats object released.
    lrsCall.verifyNextReportClusters(Collections.singletonList(new String[] {clusterName, null}));

    fakeClock.forwardNanos(1000L);
    // Currently load reporting continues (with empty stats) even if all stats objects have been
    // released.
    lrsCall.verifyNextReportClusters(Collections.<String[]>emptyList());  // no more stats reported

    // See more test on LoadReportClientTest.java
  }

  private DiscoveryRpcCall startResourceWatcher(
      ResourceType type, String name, ResourceWatcher watcher) {
    FakeClock.TaskFilter timeoutTaskFilter;
    switch (type) {
      case LDS:
        timeoutTaskFilter = LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchLdsResource(name, (LdsResourceWatcher) watcher);
        break;
      case RDS:
        timeoutTaskFilter = RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchRdsResource(name, (RdsResourceWatcher) watcher);
        break;
      case CDS:
        timeoutTaskFilter = CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchCdsResource(name, (CdsResourceWatcher) watcher);
        break;
      case EDS:
        timeoutTaskFilter = EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchEdsResource(name, (EdsResourceWatcher) watcher);
        break;
      case UNKNOWN:
      default:
        throw new AssertionError("should never be here");
    }
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(type, Collections.singletonList(name), "", "", NODE);
    ScheduledTask timeoutTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(timeoutTaskFilter));
    assertThat(timeoutTask.getDelay(TimeUnit.SECONDS))
        .isEqualTo(ClientXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);
    return call;
  }

  protected abstract static class DiscoveryRpcCall {

    protected abstract void verifyRequest(
        ResourceType type, List<String> resources, String versionInfo, String nonce, Node node);

    protected void verifyRequest(
        ResourceType type, String resource, String versionInfo, String nonce, Node node) {
      verifyRequest(type, ImmutableList.of(resource), versionInfo, nonce, node);
    }

    protected abstract void verifyNoMoreRequest();

    protected abstract void sendResponse(
        ResourceType type, List<Any> resources, String versionInfo, String nonce);

    protected void sendResponse(ResourceType type, Any resource, String versionInfo, String nonce) {
      sendResponse(type, ImmutableList.of(resource), versionInfo, nonce);
    }

    protected abstract void sendError(Throwable t);

    protected abstract void sendCompleted();
  }

  protected abstract static class LrsRpcCall {

    /**
     * Verifies a LRS request has been sent with ClusterStats of the given list of clusters.
     */
    protected abstract void verifyNextReportClusters(List<String[]> clusters);

    protected abstract void sendResponse(List<String> clusters, long loadReportIntervalNano);
  }

  protected abstract static class MessageFactory {

    protected final Message buildListener(String name, Message routeConfiguration) {
      return buildListener(name, routeConfiguration, Collections.<Message>emptyList());
    }

    @SuppressWarnings("unchecked")
    protected abstract Message buildListener(
        String name, Message routeConfiguration, List<? extends Message> httpFilters);

    protected abstract Message buildListenerForRds(String name, String rdsResourceName);

    protected abstract Message buildHttpFilter(String name, @Nullable Any typedConfig);

    protected abstract Any buildHttpFaultTypedConfig(
        @Nullable Long delayNanos, @Nullable Integer delayRate, String upstreamCluster,
        List<String> downstreamNodes, @Nullable Integer maxActiveFaults, @Nullable Status status,
        @Nullable Integer httpCode, @Nullable Integer abortRate);

    protected abstract Message buildRouteConfiguration(String name,
        List<Message> virtualHostList);

    protected abstract List<Message> buildOpaqueVirtualHosts(int num);

    protected abstract Message buildVirtualHost(
        List<? extends Message> routes, Map<String, Any> typedConfigMap);

    protected abstract List<? extends Message> buildOpaqueRoutes(int num);

    protected abstract Message buildEdsCluster(String clusterName, @Nullable String edsServiceName,
        String lbPolicy, @Nullable Message ringHashLbConfig, boolean enableLrs,
        @Nullable Message upstreamTlsContext, @Nullable Message circuitBreakers);

    protected abstract Message buildLogicalDnsCluster(String clusterName, String lbPolicy,
        @Nullable Message ringHashLbConfig, boolean enableLrs,
        @Nullable Message upstreamTlsContext, @Nullable Message circuitBreakers);

    protected abstract Message buildAggregateCluster(String clusterName, String lbPolicy,
        @Nullable Message ringHashLbConfig, List<String> clusters);

    protected abstract Message buildRingHashLbConfig(String hashFunction, long minRingSize,
        long maxRingSize);

    protected abstract Message buildUpstreamTlsContext(String secretName, String targetUri);

    protected abstract Message buildCircuitBreakers(int highPriorityMaxRequests,
        int defaultPriorityMaxRequests);

    protected abstract Message buildClusterLoadAssignment(String cluster,
        List<Message> localityLbEndpoints, List<Message> dropOverloads);

    protected abstract Message buildLocalityLbEndpoints(String region, String zone, String subZone,
        List<Message> lbEndpointList, int loadBalancingWeight, int priority);

    protected abstract Message buildLbEndpoint(String address, int port, String healthStatus,
        int lbWeight);

    protected abstract Message buildDropOverload(String category, int dropPerMillion);
  }
}
