/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.internal.ManagedChannelImplBuilder.ChannelBuilderDefaultPortProvider;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link ManagedChannelImplBuilder}.
 */
@RunWith(JUnit4.class)
public class ManagedChannelImplBuilderTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final static String DUMMY_TARGET = "fake";

//  @Rule
//  public final ExpectedException thrown = ExpectedException.none();

//  private final ManagedChannelImplBuilder builder = new ManagedChannelImplBuilder("fake",
//      new ClientTransportFactoryBuilder() {
//        @Override
//        public ClientTransportFactory buildClientTransportFactory() {
//          return null;
//        }
//      },
//      new ChannelBuilderDefaultPortProvider() {
//        @Override
//        public int getDefaultPort() {
//          return 42;
//        }
//      }
//  );

  @Mock private ClientTransportFactoryBuilder mockClientTransportFactoryBuilder;
  @Mock private ChannelBuilderDefaultPortProvider mockChannelBuilderDefaultPortProvider;
//  private final ManagedChannelImplBuilder builder = new ManagedChannelImplBuilder("fake",
//      clientTransportFactoryBuilderMock,
//      channelBuilderDefaultPortProviderMock);

//  public void setUp() throws Exception {
//  }
//
//  @After
//  public void tearDown() throws Exception {
//  }
//
//  @Test
//  public void buildTransportFactory() {
//  }

  @Test
  public void getDefaultPort_default() {
    final ManagedChannelImplBuilder builderNoPortProvider = new ManagedChannelImplBuilder(
        DUMMY_TARGET, mockClientTransportFactoryBuilder, null);

    assertEquals(GrpcUtil.DEFAULT_PORT_SSL, builderNoPortProvider.getDefaultPort());
  }

  @Test
  public void getDefaultPort_custom() {
    final int DUMMY_PORT = 42;
    when(mockChannelBuilderDefaultPortProvider.getDefaultPort()).thenReturn(DUMMY_PORT);

    final ManagedChannelImplBuilder builder = new ManagedChannelImplBuilder(
        DUMMY_TARGET, mockClientTransportFactoryBuilder, mockChannelBuilderDefaultPortProvider);

    assertEquals(DUMMY_PORT, builder.getDefaultPort());
    verify(mockChannelBuilderDefaultPortProvider).getDefaultPort();
  }

//  @Test
//  public void disableCheckAuthority() {
//  }
//
//  @Test
//  public void enableCheckAuthority() {
//  }
//
//  @Test
//  public void overrideAuthorityChecker() {
//  }
//
//  @Test
//  public void checkAuthority() {
//  }
//
//  @Test
//  public void setStatsEnabled() {
//  }
//
//  @Test
//  public void setStatsRecordStartedRpcs() {
//  }
//
//  @Test
//  public void setStatsRecordFinishedRpcs() {
//  }
//
//  @Test
//  public void setStatsRecordRealTimeMetrics() {
//  }
//
//  @Test
//  public void setTracingEnabled() {
//  }
//
//  @Test
//  public void getOffloadExecutorPool() {
//  }
}
