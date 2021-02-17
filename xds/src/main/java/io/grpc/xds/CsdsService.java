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
import io.envoyproxy.envoy.service.status.v3.ClientConfig;
import io.envoyproxy.envoy.service.status.v3.ClientStatusDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.status.v3.ClientStatusRequest;
import io.envoyproxy.envoy.service.status.v3.ClientStatusResponse;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.ObjectPool;
import io.grpc.stub.StreamObserver;

// TODO(sergiitk): finish description, update since.
/**
 * The CSDS service provides information about the status of a running xDS client.
 *
 * @since 1.37.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/TODO")
public final class CsdsService extends
    ClientStatusDiscoveryServiceGrpc.ClientStatusDiscoveryServiceImplBase {
  // private static final ImmutableMap<ResourceType, Class<?>> RESOURCE_TO_CONFIG_DUMP_CLASS =
  //     new ImmutableMap.Builder<ResourceType, Class<?>>()
  //         .put(ResourceType.LDS, ListenersConfigDump.class)
  //         .put(ResourceType.RDS, RoutesConfigDump.class)
  //         .put(ResourceType.CDS, ClustersConfigDump.class)
  //         .put(ResourceType.EDS, EndpointsConfigDump.class)
  //         .build();

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
    } catch (StatusRuntimeException e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public StreamObserver<ClientStatusRequest> streamClientStatus(
      final StreamObserver<ClientStatusResponse> responseObserver) {
    return new StreamObserver<ClientStatusRequest>() {
      @Override
      public void onNext(ClientStatusRequest request) {
        // TODO(sergiitk): should xdsClient be managed per instance and returned at the end?
        // TODO(sergiitk): handling errors same as for the unary call?
        responseObserver.onNext(getConfigDumpForRequest(request));
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

  private ClientStatusResponse getConfigDumpForRequest(ClientStatusRequest request) {
    ObjectPool<XdsClient> xdsClientPool = null;
    XdsClient xdsClient = null;

    try {
      xdsClientPool = xdsClientPoolProvider.getXdsClientPool();
      xdsClient = xdsClientPool.getObject();

      // TODO(sergiitk): consider pulling up .node/.getNode() to XdsClient,
      // TODO(sergiitk): or xdsClientPool.getObject() returning AbstractXdsClient
      if (!(xdsClient instanceof AbstractXdsClient)) {
        throw new StatusRuntimeException(
            Status.INTERNAL.withDescription("Unexpected XdsClient implementation"));
      }

      // TODO(sergiitk): handle request.getNodeMatchers()?
      // Return single clientConfig describing {@link XdsClient} singleton.
      return ClientStatusResponse.newBuilder()
          .addConfig(getClientConfigForXdsClient((AbstractXdsClient) xdsClient))
          .build();
    } catch (XdsInitializationException e) {
      throw new StatusRuntimeException(Status.INTERNAL.withCause(e));
    } finally {
      if (xdsClient != null) {
        xdsClientPool.returnObject(xdsClient);
      }
    }
  }

  private ClientConfig getClientConfigForXdsClient(AbstractXdsClient xdsClient) {
    ClientConfig.Builder clientConfig = ClientConfig.newBuilder();

    // Add node info loaded from bootstrap.
    clientConfig.setNode(xdsClient.node.toEnvoyProtoNode());

    // String ldsVersion = xdsClient.getCurrentVersion(ResourceType.LDS);
    // ListenersConfigDump.Builder ldsConfigDump = ListenersConfigDump.newBuilder()
    //     .setVersionInfo(ldsVersion);
    // if (!ldsVersion.equals("")) {
    //   // ...
    // }

    return clientConfig.build();
  }
  //
  // private ClientStatusResponse getConfigDumpForRequest(
  //     ClientStatusRequest request, AbstractXdsClient xdsClient) {
  //
  //
  //   ClientConfig.Builder clientConfig = ClientConfig.newBuilder();
  //   return ClientStatusResponse.newBuilder().addConfig(clientConfig).build();
  //
  //   // Return single clientConfig describing xdsClient.
  //   // Add node info loaded from bootstrap.
  //   clientConfig.setNode(xdsClient.node.toEnvoyProtoNode());
  //
  //   // for (ResourceType type : RESOURCE_TO_CONFIG_DUMP_CLASS.keySet()) {
  //   //   addConfigDump(type, clientConfig, xdsClient);
  //   // }
  //   // LDS
  //   String ldsVersion = xdsClient.getCurrentVersion(ResourceType.LDS);
  //   if (!ldsVersion.equals("")) {
  //     ListenersConfigDump.Builder ldsConfigDump =
  //         ListenersConfigDump.newBuilder().setVersionInfo(ldsVersion);
  //     clientConfig.addXdsConfig(
  //         PerXdsConfig.newBuilder().setListenerConfig(ldsConfigDump.build()).build());
  //   }
  //
  //   return resp.build();
  // }
  //
  // // private void addConfigDump(
  // //     ResourceType type, ClientConfig.Builder configDump, AbstractXdsClient xdsClient) {
  // //     if (type == ResourceType.UNKNOWN) {
  // //       return;
  // //     }
  // //
  // // }
}
