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

package io.grpc.netty;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NettyAdaptiveCumulatorTest {
  private NettyAdaptiveCumulator cumulator;
  private ByteBufAllocator alloc = UnpooledByteBufAllocator.DEFAULT;
  private ByteBuf in = ByteBufUtil.writeAscii(alloc, "New data!");
  private ByteBuf buf = ByteBufUtil.writeAscii(alloc, "Some data.");

  @Before
  public void setUp() throws Exception {
    cumulator = new NettyAdaptiveCumulator();
  }

  @After
  public void tearDown() throws Exception {
    while (in.refCnt() != 0) {
      in.release();
    }
    while (buf.refCnt() != 0) {
      buf.release();
    }
  }

  @Test
  public void cumulate_emptyUseInputDirectly() {
    ByteBuf emptyCumulation = alloc.buffer().writeZero(1).skipBytes(1);
    ByteBuf cumulation = cumulator.cumulate(alloc, emptyCumulation, in);
    assertSame(in, cumulation);
    assertEquals(0, emptyCumulation.refCnt());
  }

  @Test
  public void cumulate_nonCompositeCumulation() {
    ByteBuf cumulation = cumulator.cumulate(alloc, buf, in);
    assertThat(cumulation).isInstanceOf(CompositeByteBuf.class);
    CompositeByteBuf composite = (CompositeByteBuf) cumulation;
    assertEquals(buf, composite.component(0));
    assertEquals(in, composite.component(1));
  }

  @Test
  public void cumulate_compositeCumulation() {
    CompositeByteBuf composite = alloc.compositeBuffer(2).addComponent(true, buf);
    ByteBuf cumulation = cumulator.cumulate(alloc, composite, in);
    assertSame(cumulation, composite);
    assertEquals(buf, composite.component(0));
    assertEquals(in, composite.component(1));
  }
}
