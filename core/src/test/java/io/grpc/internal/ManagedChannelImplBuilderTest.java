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

import io.grpc.internal.ManagedChannelImplBuilder.ChannelBuilderDefaultPortProvider;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ManagedChannelImplBuilder}. */
@RunWith(JUnit4.class)
public class ManagedChannelImplBuilderTest {

//  @Rule
//  public final ExpectedException thrown = ExpectedException.none();

  private ManagedChannelImplBuilder builder = new ManagedChannelImplBuilder("fake",
      new ClientTransportFactoryBuilder() {
        @Override
        public ClientTransportFactory buildClientTransportFactory() {
          return null;
        }
      },
      new ChannelBuilderDefaultPortProvider() {
        @Override
        public int getDefaultPort() {
          return 42;
        }
      }
  );


//  @Before
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
    assertEquals(builder.getDefaultPort(), GrpcUtil.DEFAULT_PORT_SSL);
  }

  @Test
  public void getDefaultPort_overridden() {
    assertEquals(builder.getDefaultPort(), 42);
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