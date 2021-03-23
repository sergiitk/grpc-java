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
import com.google.protobuf.Timestamp;
import io.envoyproxy.envoy.admin.v3.ClientResourceStatus;
import io.envoyproxy.envoy.admin.v3.ClustersConfigDump;
import io.envoyproxy.envoy.admin.v3.ClustersConfigDump.DynamicCluster;
import io.envoyproxy.envoy.admin.v3.EndpointsConfigDump;
import io.envoyproxy.envoy.admin.v3.EndpointsConfigDump.DynamicEndpointConfig;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump.DynamicListener;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump.DynamicListenerState;
import io.envoyproxy.envoy.admin.v3.RoutesConfigDump;
import io.envoyproxy.envoy.admin.v3.RoutesConfigDump.DynamicRouteConfig;
import io.envoyproxy.envoy.service.status.v3.ClientConfig;
import io.envoyproxy.envoy.service.status.v3.ClientStatusDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.status.v3.ClientStatusRequest;
import io.envoyproxy.envoy.service.status.v3.ClientStatusResponse;
import io.envoyproxy.envoy.service.status.v3.PerXdsConfig;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.ObjectPool;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.XdsClient.ResourceMetadata;
import io.grpc.xds.XdsClient.ResourceMetadata.ResourceMetadataStatus;
import io.grpc.xds.XdsClient.ResourceMetadata.UpdateFailureState;
import java.util.Map;

// TODO(sergiitk): finish description, update since.
/**
 * The CSDS service provides information about the status of a running xDS client.
 *
 * @since 1.37.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/TODO")
public final class CsdsService extends
    ClientStatusDiscoveryServiceGrpc.ClientStatusDiscoveryServiceImplBase {
  private final SharedXdsClientPoolProvider xdsClientPoolProvider;

  private CsdsService() {
    this(SharedXdsClientPoolProvider.getDefaultProvider());
  }

  @VisibleForTesting
  CsdsService(SharedXdsClientPoolProvider xdsClientPoolProvider) {
    this.xdsClientPoolProvider = checkNotNull(xdsClientPoolProvider, "xdsClientPoolProvider");
  }

  /** Creates an instance. */
  public static CsdsService newInstance() {
    return new CsdsService();
  }

  @Override
  public void fetchClientStatus(
      ClientStatusRequest request, StreamObserver<ClientStatusResponse> responseObserver) {
    if (handleRequest(request, responseObserver)) {
      responseObserver.onCompleted();
    }
  }

  @Override
  public StreamObserver<ClientStatusRequest> streamClientStatus(
      final StreamObserver<ClientStatusResponse> responseObserver) {
    return new StreamObserver<ClientStatusRequest>() {
      @Override
      public void onNext(ClientStatusRequest request) {
        handleRequest(request, responseObserver);
      }

      @Override
      public void onError(Throwable t) {
        onCompleted();
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    };
  }

  private boolean handleRequest(
      ClientStatusRequest request, StreamObserver<ClientStatusResponse> responseObserver) {
    try {
      responseObserver.onNext(getConfigDumpForRequest(request));
      return true;
    } catch (StatusException e) {
      responseObserver.onError(e);
    } catch (Exception e) {
      responseObserver.onError(new StatusException(Status.INTERNAL.withCause(e)));
    }
    return false;
  }

  private ClientStatusResponse getConfigDumpForRequest(ClientStatusRequest request)
      throws StatusException {
    if (request.getNodeMatchersCount() > 0) {
      throw new StatusException(
          Status.INVALID_ARGUMENT.withDescription("node_matchers not supported"));
    }

    ObjectPool<XdsClient> xdsClientPool = xdsClientPoolProvider.getXdsClientPoolOrNull();
    if (xdsClientPool == null) {
      return ClientStatusResponse.getDefaultInstance();
    }

    XdsClient xdsClient = null;
    try {
      xdsClient = xdsClientPool.getObject();
      return ClientStatusResponse.newBuilder()
          .addConfig(getClientConfigForXdsClient(xdsClient))
          .build();
    } finally {
      if (xdsClient != null) {
        xdsClientPool.returnObject(xdsClient);
      }
    }
  }

  private ClientConfig getClientConfigForXdsClient(XdsClient xdsClient) {
    ListenersConfigDump ldsConfig = dumpLdsConfig(
        xdsClient.getSubscribedResourcesMetadata(ResourceType.LDS),
        xdsClient.getCurrentVersion(ResourceType.LDS));
    RoutesConfigDump rdsConfig = dumpRdsConfig(
        xdsClient.getSubscribedResourcesMetadata(ResourceType.RDS));
    ClustersConfigDump cdsConfig = dumpCdsConfig(
        xdsClient.getSubscribedResourcesMetadata(ResourceType.CDS),
        xdsClient.getCurrentVersion(ResourceType.CDS));
    EndpointsConfigDump edsConfig = dumpEdsConfig(
        xdsClient.getSubscribedResourcesMetadata(ResourceType.EDS));

    return ClientConfig.newBuilder()
        .setNode(xdsClient.getNode().toEnvoyProtoNode())
        .addXdsConfig(PerXdsConfig.newBuilder().setListenerConfig(ldsConfig))
        .addXdsConfig(PerXdsConfig.newBuilder().setRouteConfig(rdsConfig))
        .addXdsConfig(PerXdsConfig.newBuilder().setClusterConfig(cdsConfig))
        .addXdsConfig(PerXdsConfig.newBuilder().setEndpointConfig(edsConfig))
        .build();
  }

  @VisibleForTesting
  static ListenersConfigDump dumpLdsConfig(
      Map<String, ResourceMetadata> resourcesMetadata, String version) {
    ListenersConfigDump.Builder ldsConfig = ListenersConfigDump.newBuilder();
    for (Map.Entry<String, ResourceMetadata> entry : resourcesMetadata.entrySet()) {
      ldsConfig.addDynamicListeners(buildDynamicListener(entry.getKey(), entry.getValue()));
    }
    return ldsConfig.setVersionInfo(version).build();
  }

  @VisibleForTesting
  static DynamicListener buildDynamicListener(String name, ResourceMetadata metadata) {
    DynamicListener.Builder dynamicListener = DynamicListener.newBuilder()
        .setName(name)
        .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()));
    if (metadata.getErrorState() != null) {
      dynamicListener.setErrorState(metadataUpdateFailureStateToProto(metadata.getErrorState()));
    }
    DynamicListenerState.Builder dynamicListenerState = DynamicListenerState.newBuilder()
        .setVersionInfo(metadata.getVersion())
        .setLastUpdated(nanosToTimestamp(metadata.getUpdateTimeNanos()));
    if (metadata.getRawResource() != null) {
      dynamicListenerState.setListener(metadata.getRawResource());
    }
    return dynamicListener.setActiveState(dynamicListenerState).build();
  }

  @VisibleForTesting
  static RoutesConfigDump dumpRdsConfig(Map<String, ResourceMetadata> resourcesMetadata) {
    RoutesConfigDump.Builder rdsConfig = RoutesConfigDump.newBuilder();
    for (ResourceMetadata metadata : resourcesMetadata.values()) {
      rdsConfig.addDynamicRouteConfigs(buildDynamicRouteConfig(metadata));
    }
    return rdsConfig.build();
  }

  @VisibleForTesting
  static DynamicRouteConfig buildDynamicRouteConfig(ResourceMetadata metadata) {
    DynamicRouteConfig.Builder dynamicRouteConfig = DynamicRouteConfig.newBuilder()
        .setVersionInfo(metadata.getVersion())
        .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()))
        .setLastUpdated(nanosToTimestamp(metadata.getUpdateTimeNanos()));
    if (metadata.getErrorState() != null) {
      dynamicRouteConfig.setErrorState(metadataUpdateFailureStateToProto(metadata.getErrorState()));
    }
    if (metadata.getRawResource() != null) {
      dynamicRouteConfig.setRouteConfig(metadata.getRawResource());
    }
    return dynamicRouteConfig.build();
  }

  @VisibleForTesting
  static ClustersConfigDump dumpCdsConfig(
      Map<String, ResourceMetadata> resourcesMetadata, String version) {
    ClustersConfigDump.Builder cdsConfig = ClustersConfigDump.newBuilder();
    for (ResourceMetadata metadata : resourcesMetadata.values()) {
      cdsConfig.addDynamicActiveClusters(buildDynamicCluster(metadata));
    }
    return cdsConfig.setVersionInfo(version).build();
  }

  @VisibleForTesting
  static DynamicCluster buildDynamicCluster(ResourceMetadata metadata) {
    DynamicCluster.Builder dynamicCluster = DynamicCluster.newBuilder()
        .setVersionInfo(metadata.getVersion())
        .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()))
        .setLastUpdated(nanosToTimestamp(metadata.getUpdateTimeNanos()));
    if (metadata.getErrorState() != null) {
      dynamicCluster.setErrorState(metadataUpdateFailureStateToProto(metadata.getErrorState()));
    }
    if (metadata.getRawResource() != null) {
      dynamicCluster.setCluster(metadata.getRawResource());
    }
    return dynamicCluster.build();
  }

  @VisibleForTesting
  static EndpointsConfigDump dumpEdsConfig(Map<String, ResourceMetadata> resourcesMetadata) {
    EndpointsConfigDump.Builder edsConfig = EndpointsConfigDump.newBuilder();
    for (ResourceMetadata metadata : resourcesMetadata.values()) {
      edsConfig.addDynamicEndpointConfigs(buildDynamicEndpointConfig(metadata));
    }
    return edsConfig.build();
  }

  @VisibleForTesting
  static DynamicEndpointConfig buildDynamicEndpointConfig(ResourceMetadata metadata) {
    DynamicEndpointConfig.Builder dynamicRouteConfig = DynamicEndpointConfig.newBuilder()
        .setVersionInfo(metadata.getVersion())
        .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()))
        .setLastUpdated(nanosToTimestamp(metadata.getUpdateTimeNanos()));
    if (metadata.getErrorState() != null) {
      dynamicRouteConfig.setErrorState(metadataUpdateFailureStateToProto(metadata.getErrorState()));
    }
    if (metadata.getRawResource() != null) {
      dynamicRouteConfig.setEndpointConfig(metadata.getRawResource());
    }
    return dynamicRouteConfig.build();
  }

  private static Timestamp nanosToTimestamp(long updateTimeNanos) {
    long exponent = 1_000_000_000L;
    long seconds = updateTimeNanos / exponent;
    int nanos = (int) (updateTimeNanos - seconds * exponent);
    return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private static io.envoyproxy.envoy.admin.v3.UpdateFailureState metadataUpdateFailureStateToProto(
      UpdateFailureState errorState) {
    return io.envoyproxy.envoy.admin.v3.UpdateFailureState.newBuilder()
        .setLastUpdateAttempt(nanosToTimestamp(errorState.getFailedUpdateTimeNanos()))
        .setDetails(errorState.getFailedDetails())
        .setVersionInfo(errorState.getFailedVersion())
        .build();
  }

  private static ClientResourceStatus metadataStatusToClientStatus(ResourceMetadataStatus status) {
    switch (status) {
      case UNKNOWN:
        return ClientResourceStatus.UNKNOWN;
      case DOES_NOT_EXIST:
        return ClientResourceStatus.DOES_NOT_EXIST;
      case REQUESTED:
        return ClientResourceStatus.REQUESTED;
      case ACKED:
        return ClientResourceStatus.ACKED;
      case NACKED:
        return ClientResourceStatus.NACKED;
      default:
        throw new AssertionError("Unexpected ResourceMetadataStatus: " + status);
    }
  }
}
