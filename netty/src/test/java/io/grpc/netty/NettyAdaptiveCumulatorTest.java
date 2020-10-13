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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.netty.NettyAdaptiveCumulator.AdaptiveCumulatorConsolidateHeuristic;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.CharsetUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NettyAdaptiveCumulatorTest {
  private NettyAdaptiveCumulator cumulator;
  private ByteBufAllocator alloc = new UnpooledByteBufAllocator(false);
  private ByteBuf in = ByteBufUtil.writeAscii(alloc, "New data!");
  private ByteBuf buf = ByteBufUtil.writeAscii(alloc, "Some data.");
  private CompositeByteBuf composite = alloc.compositeBuffer(16);
  private ByteBuf cumulation;

  @Before
  public void setUp() {
    cumulator = new NettyAdaptiveCumulator(new AdaptiveCumulatorConsolidateHeuristic() {
      @Override public int consolidateLast(CompositeByteBuf composite) {
        return 0;
      }
    });
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
    cumulation = cumulator.cumulate(alloc, buf, in);
    assertThat(cumulation).isInstanceOf(CompositeByteBuf.class);
    CompositeByteBuf composite = (CompositeByteBuf) cumulation;
    assertEquals(buf, composite.component(0));
    assertEquals(in, composite.component(1));
  }

  @Test
  public void cumulate_compositeCumulation() {
    composite = alloc.compositeBuffer(2).addComponent(true, buf);
    cumulation = cumulator.cumulate(alloc, composite, in);
    assertSame(cumulation, composite);
    assertEquals(buf, composite.component(0));
    assertEquals(in, composite.component(1));
  }

  @Test
  public void cumulate_inputReleasedOnError() {
    final UnsupportedOperationException expectedError = new UnsupportedOperationException();
    composite = new CompositeByteBuf(alloc, false, 16, buf) {
      @Override
      public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex, ByteBuf buffer) {
        throw expectedError;
      }
    };

    try {
      cumulation = cumulator.cumulate(alloc, composite, in);
      fail("Cumulator didn't throw");
    } catch (UnsupportedOperationException actualError) {
      assertSame(expectedError, actualError);
      assertEquals(0, in.refCnt());
    }
  }

  @Test
  public void cumulate_newCumulationReleasedOnError() {
    final UnsupportedOperationException expectedError = new UnsupportedOperationException();
    composite = new CompositeByteBuf(alloc, false, 16) {
      @Override
      @SuppressWarnings("ReferenceEquality")
      public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex, ByteBuf buffer) {
        // Allow to add previous cumulation to the new one.
        if (buffer == buf) {
          return super.addFlattenedComponents(increaseWriterIndex, buffer);
        }
        throw expectedError;
      }
    };

    alloc = mock(AbstractByteBufAllocator.class);
    when(alloc.compositeBuffer(anyInt())).thenReturn(composite);

    try {
      cumulation = cumulator.cumulate(alloc, buf, in);
      fail("Cumulator didn't throw");
    } catch (UnsupportedOperationException actualError) {
      assertSame(expectedError, actualError);
      assertEquals(0, in.refCnt());
      assertEquals(0, composite.refCnt());
    }
  }

  @Test
  public void cumulate_consolidatesComponents_notCalled() {
    composite = new CompositeByteBuf(alloc, false, 16) {
      @Override public CompositeByteBuf consolidate(int cIndex, int numComponents) {
        throw new UnsupportedOperationException("Must not be called");
      }
    };
    composite.addComponent(true, buf);

    cumulation = cumulator.cumulate(alloc, composite, in);
    CompositeByteBuf composite = (CompositeByteBuf) cumulation;
    assertEquals(buf, composite.component(0));
    assertEquals(in, composite.component(1));
  }

  @Test
  public void cumulate_consolidatesComponents_consolidateAll() {
    cumulator = new NettyAdaptiveCumulator(new AdaptiveCumulatorConsolidateHeuristic() {
      @Override public int consolidateLast(CompositeByteBuf composite) {
        return 2;
      }
    });

    String expectedResult = buf.toString(CharsetUtil.US_ASCII) + in.toString(CharsetUtil.US_ASCII);
    composite.addComponent(true, buf);
    cumulation = cumulator.cumulate(alloc, composite, in);

    CompositeByteBuf composite = (CompositeByteBuf) cumulation;
    assertEquals(1, composite.numComponents());
    assertEquals(expectedResult, composite.toString(CharsetUtil.US_ASCII));
  }

  @Test
  public void cumulate_consolidatesComponents_consolidatePart() {
    cumulator = new NettyAdaptiveCumulator(new AdaptiveCumulatorConsolidateHeuristic() {
      @Override public int consolidateLast(CompositeByteBuf composite) {
        return 2;
      }
    });

    String expectedComp0 = buf.toString(CharsetUtil.US_ASCII);
    String expectedComp1 = buf.toString(CharsetUtil.US_ASCII)  + in.toString(CharsetUtil.US_ASCII);

    composite.addComponent(true, buf);
    composite.addComponent(true, buf.copy());
    cumulation = cumulator.cumulate(alloc, composite, in);

    CompositeByteBuf composite = (CompositeByteBuf) cumulation;
    assertEquals(2, composite.numComponents());
    assertEquals(expectedComp0, composite.component(0).toString(CharsetUtil.US_ASCII));
    assertEquals(expectedComp1, composite.component(1).toString(CharsetUtil.US_ASCII));
  }
}
