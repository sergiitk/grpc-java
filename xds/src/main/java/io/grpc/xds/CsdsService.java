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
// TODO(sergiitk): v3 vs v2 service
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
    ClientStatusResponse resp;

    ObjectPool<XdsClient> xdsClientPool = null;
    XdsClient xdsClient = null;

    try {
      xdsClientPool = xdsClientPoolProvider.getXdsClientPool();
      xdsClient = xdsClientPool.getObject();

      if (!(xdsClient instanceof AbstractXdsClient)) {
        throw new StatusRuntimeException(
            Status.FAILED_PRECONDITION.withDescription("Unexpected XdsClient implementation"));
      }

      AbstractXdsClient concreteXdsClient = (AbstractXdsClient) xdsClient;

      ClientConfig.Builder configDump = ClientConfig
          .newBuilder()
          .setNode(concreteXdsClient.node.toEnvoyProtoNode());
      // Node.newBuilder().setId("Hello world")

      // TODO(sergiitk): xDS services to xDS Config

      resp = ClientStatusResponse.newBuilder().addConfig(configDump.build()).build();
    } catch (StatusRuntimeException | XdsInitializationException e) {
      responseObserver.onError(e);
      return;
    } finally {
      if (xdsClient != null) {
        xdsClientPool.returnObject(xdsClient);
      }
    }

    responseObserver.onNext(resp);
    responseObserver.onCompleted();
  }
}
