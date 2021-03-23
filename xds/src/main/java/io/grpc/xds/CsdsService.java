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
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump.DynamicListener;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump.DynamicListenerState;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    try {
      responseObserver.onNext(getConfigDumpForRequest(request));
      responseObserver.onCompleted();
    } catch (StatusException e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public StreamObserver<ClientStatusRequest> streamClientStatus(
      final StreamObserver<ClientStatusResponse> responseObserver) {
    return new StreamObserver<ClientStatusRequest>() {
      @Override
      public void onNext(ClientStatusRequest request) {
        // TODO(sergiitk): common method to handle errors?
        try {
          responseObserver.onNext(getConfigDumpForRequest(request));
        } catch (StatusException e) {
          responseObserver.onError(e);
        }
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
    ListenersConfigDump ldsConfig = dumpLdsConfig(xdsClient.getCurrentVersion(ResourceType.LDS),
        xdsClient.getSubscribedResourcesMetadata(ResourceType.LDS));

    return ClientConfig.newBuilder()
        .setNode(xdsClient.getNode().toEnvoyProtoNode())
        .addXdsConfig(PerXdsConfig.newBuilder().setListenerConfig(ldsConfig))
        .build();
  }

  private static ListenersConfigDump dumpLdsConfig(
      String version, Map<String, ResourceMetadata> resourcesMetadata) {
    ListenersConfigDump.Builder ldsConfig = ListenersConfigDump.newBuilder();
    for (Map.Entry<String, ResourceMetadata> entry : resourcesMetadata.entrySet()) {
      ldsConfig.addDynamicListeners(buildDynamicListener(entry.getKey(), entry.getValue()));
    }
    return ldsConfig.setVersionInfo(version).build();
  }

  @VisibleForTesting
  static DynamicListener buildDynamicListener(String name, ResourceMetadata metadata) {
    DynamicListenerState.Builder listenerState = DynamicListenerState.newBuilder()
        .setVersionInfo(metadata.getVersion())
        .setLastUpdated(nanosToTimestamp(metadata.getUpdateTimeNanos()));
    if (metadata.getRawResource() != null) {
      listenerState.setListener(metadata.getRawResource());
    }
    // TODO(sergiitk): Add error state if present.
    return DynamicListener.newBuilder()
        .setName(name)
        .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()))
        .setActiveState(listenerState)
        .build();
  }

  private static Timestamp nanosToTimestamp(long updateTimeNanos) {
    // TODO(sergiitk): build directly from nanos.
    long millis = TimeUnit.NANOSECONDS.toMillis(updateTimeNanos);
    // See https://developers.google.com/protocol-buffers/docs/reference/google.protobuf#google.protobuf.Timestamp
    return Timestamp.newBuilder()
        .setSeconds(millis / 1000)
        .setNanos((int) ((millis % 1000) * 1000000))
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
