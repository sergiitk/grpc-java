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
import static io.netty.util.CharsetUtil.US_ASCII;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(Enclosed.class)
public class NettyAdaptiveCumulatorTest {
  abstract public static class SharedNettyAdaptiveCumulatorTest {
    // Represent data as immutable ASCII Strings for easy and readable ByteBuf equality assertions.
    protected static final String DATA_INITIAL = "0123";
    protected static final String DATA_INCOMING = "456789";
    protected static final String DATA_CUMULATED = "0123456789";
    protected NettyAdaptiveCumulator cumulator;
    protected ByteBufAllocator alloc = new UnpooledByteBufAllocator(false);
    protected ByteBuf contiguous = ByteBufUtil.writeAscii(alloc, DATA_INITIAL);
    protected CompositeByteBuf composite = alloc.compositeBuffer().addComponent(true, contiguous);
    protected ByteBuf in = ByteBufUtil.writeAscii(alloc, DATA_INCOMING);
  }

  @RunWith(JUnit4.class)
  public static class CumulateTests extends SharedNettyAdaptiveCumulatorTest {
    @Before
    public void setUp() {
      cumulator = new NettyAdaptiveCumulator(0) {
        @Override
        ByteBuf mergeIfNeeded(ByteBufAllocator alloc, CompositeByteBuf composite, ByteBuf in) {
          // To restrict testing scope to NettyAdaptiveCumulator.cumulate(), return input buf as is.
          return in;
        }
      };
    }

    @Test
    public void notReadableCumulation_replacedWithInputAndReleased() {
      contiguous.readerIndex(contiguous.writerIndex());
      assertFalse(contiguous.isReadable());
      ByteBuf cumulation = cumulator.cumulate(alloc, contiguous, in);
      assertEquals(DATA_INCOMING, cumulation.toString(US_ASCII));
      assertEquals(0, contiguous.refCnt());
    }

    @Test
    public void contiguousCumulation_newCompositeFromContiguousAndInput() {
      CompositeByteBuf cumulation = (CompositeByteBuf) cumulator.cumulate(alloc, contiguous, in);
      assertEquals(DATA_INITIAL, cumulation.component(0).toString(US_ASCII));
      assertEquals(DATA_INCOMING, cumulation.component(1).toString(US_ASCII));
      assertEquals(DATA_CUMULATED, cumulation.toString(US_ASCII));
    }

    @Test
    public void compositeCumulation_inputAppendedAsANewComponent() {
      assertSame(composite, cumulator.cumulate(alloc, composite, in));
      assertEquals(DATA_INITIAL, composite.component(0).toString(US_ASCII));
      assertEquals(DATA_INCOMING, composite.component(1).toString(US_ASCII));
      assertEquals(DATA_CUMULATED, composite.toString(US_ASCII));
    }

    @Test
    public void compositeCumulation_inputReleasedOnError() {
      final UnsupportedOperationException expectedError = new UnsupportedOperationException();
      composite = new CompositeByteBuf(alloc, false, Integer.MAX_VALUE, contiguous) {
        @Override
        public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex,
            ByteBuf buffer) {
          throw expectedError;
        }
      };
      try {
        cumulator.cumulate(alloc, composite, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(expectedError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // Initial composite cumulation owned by the caller in this case, so it isn't released.
        assertThat(composite.refCnt()).isAtLeast(1);
      }
    }

    @Test
    public void contiguousCumulation_inputAndNewCompositeReleasedOnError() {
      final UnsupportedOperationException expectedError = new UnsupportedOperationException();
      composite = new CompositeByteBuf(alloc, false, Integer.MAX_VALUE) {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex,
            ByteBuf buffer) {
          if (this.numComponents() == 0) {
            // Add the initial cumulation to a new composite cumulation.
            return super.addFlattenedComponents(increaseWriterIndex, buffer);
          }
          // Emulate an error on any other attempts to add a component.
          throw expectedError;
        }
      };
      // Return our instance of new composite to ensure it's released.
      alloc = mock(AbstractByteBufAllocator.class);
      when(alloc.compositeBuffer(anyInt())).thenReturn(composite);
      try {
        cumulator.cumulate(alloc, contiguous, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(expectedError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // New composite cumulation hasn't been returned to the caller, so it must be released.
        assertEquals(0, composite.refCnt());
      }
    }
  }

  @RunWith(JUnit4.class)
  public static class MergeIfNeededTests extends SharedNettyAdaptiveCumulatorTest {
    @Before
    public void setUp() {
      // cumulator = new NettyAdaptiveCumulator(DATA_INCOMING.length());
    }

    @Test
    public void skip_emptyComposite() {
      composite = alloc.compositeBuffer();
      ByteBuf component = NettyAdaptiveCumulator
          .mergeTailAndInputIfBelowComposeMinSize(alloc, composite, in, Integer.MAX_VALUE);
      // Unmodified input returned
      assertSame(in, component);
      assertEquals(DATA_INCOMING, component.toString(US_ASCII));
      // Composite unchanged
      assertEquals(0, composite.numComponents());
    }

    @Test
    public void skip_notBelowMinSize() {
      ByteBuf component = NettyAdaptiveCumulator
          .mergeTailAndInputIfBelowComposeMinSize(alloc, composite, in, DATA_CUMULATED.length());
      // Unmodified input returned
      assertSame(in, component);
      assertEquals(DATA_INCOMING, component.toString(US_ASCII));
      // Composite unchanged
      assertEquals(1, composite.numComponents());
      assertEquals(DATA_INITIAL, composite.toString(US_ASCII));
    }

    @Test
    public void expandTail_write() {
      // Create tail with 5 writable bytes left.
      ByteBuf tail = alloc.buffer(10, 10).setIndex(3, 5);
      tail.setCharSequence(0, "01234", US_ASCII);
      composite.addComponent(true, tail);

      // Input has 5 readable bytes left.
      in = alloc.buffer(10, 10).setIndex(5, 10);
      in.setCharSequence(0, "xxxxx56789", US_ASCII);

      // The tail and input together are below the threshold.
      cumulator = new NettyAdaptiveCumulator(11);
      ByteBuf component = cumulator.mergeIfNeeded(alloc, composite, in);
      // Composite buf must only contain the initial data.
      assertEquals(1, composite.numComponents());

      // Modified tail is returned, but it must be the same object.
      assertSame(tail, component);
      // Read (discardable) bytes of the tail must stay as is.
      // Read (discardable) bytes of the input must be discarded.
      // Readable part of the input must be appended to the tail.
      assertEquals("0123456789", tail.toString(0, 10, US_ASCII));
      assertEquals(10, component.capacity());
      assertEquals(10, component.maxCapacity());
      assertEquals(3, component.readerIndex());
      assertEquals(10, component.writerIndex());
      assertEquals(1, component.refCnt());

      // Input buf must be released and have no readable bytes.
      assertEquals(0, in.refCnt());
      assertEquals(0, in.readableBytes());
    }

    @Test
    public void expandTail_fastWrite() {
      // Use pooled allocator to test for maxFastWritableBytes() being different from writableBytes().
      alloc = new PooledByteBufAllocator();

      // Create tail with no writable bytes left, but allow to expand it.
      ByteBuf tail = alloc.buffer(5, 512).setIndex(3, 5);
      tail.setCharSequence(0, "01234", US_ASCII);
      composite.addComponent(true, tail);
      int tailFastCapacity = tail.writerIndex() + tail.maxFastWritableBytes();

      // Input has 5 readable bytes left.
      in = alloc.buffer(10, 10).setIndex(5, 10);
      in.setCharSequence(0, "xxxxx56789", US_ASCII);

      // The tail and input together are below the threshold.
      cumulator = new NettyAdaptiveCumulator(11);
      ByteBuf component = cumulator.mergeIfNeeded(alloc, composite, in);
      // Composite buf must only contain the initial data.
      assertEquals(1, composite.numComponents());

      // Modified tail is returned, but it must be the same object.
      assertSame(tail, component);
      // Read (discardable) bytes of the tail must stay as is.
      // Read (discardable) bytes of the input must be discarded.
      // Readable part of the input must be appended to the tail.
      assertEquals("0123456789", tail.toString(0, 10, US_ASCII));
      assertEquals(tailFastCapacity, component.capacity());
      assertEquals(512, component.maxCapacity());
      assertEquals(3, component.readerIndex());
      assertEquals(10, component.writerIndex());
      assertEquals(1, component.refCnt());

      // Input buf must be released and have no readable bytes.
      assertEquals(0, in.refCnt());
      assertEquals(0, in.readableBytes());
    }

    @Test
    public void expandTail_reallocate() {
      // Use pooled allocator to test for maxFastWritableBytes() being different from writableBytes().
      alloc = new PooledByteBufAllocator();

      // Create tail with no writable bytes left, but allow to expand it.
      final int tailBytes = 5;
      ByteBuf tail = alloc.buffer(tailBytes).setIndex(3, tailBytes);
      tail.setCharSequence(0, "01234", US_ASCII);
      composite.addComponent(true, tail);
      final int tailFastCapacity = tail.writerIndex() + tail.maxFastWritableBytes();

      // Make input larger than tailFastCapacity
      in = alloc.buffer(tailFastCapacity + 1).writeZero(tailFastCapacity).writeByte(1);
      byte[] expectedInput = new byte[tailFastCapacity + 1];
      in.getBytes(0, expectedInput);

      // Force merge.
      cumulator = new NettyAdaptiveCumulator(Integer.MAX_VALUE);
      ByteBuf component = cumulator.mergeIfNeeded(alloc, composite, in);
      // Composite buf must only contain the initial data.
      assertEquals(1, composite.numComponents());

      // Modified tail is returned, but it must be the same object.
      assertSame(tail, component);
      // Read (discardable) bytes of the tail must stay as is.
      assertEquals("01234", tail.toString(0, 5, US_ASCII));

      // Ensure the input is appended.
      byte[] appendedInput = new byte[tailFastCapacity + 1];
      component.getBytes(tailBytes, appendedInput);
      assertArrayEquals(expectedInput, appendedInput);
      int totalBytes = tailBytes + tailFastCapacity + 1;
      assertEquals(alloc.calculateNewCapacity(totalBytes, Integer.MAX_VALUE), component.capacity());
      assertEquals(totalBytes, component.writerIndex());
      assertEquals(3, component.readerIndex());
      assertEquals(1, component.refCnt());

      // Input buf must be released and have no readable bytes.
      assertEquals(0, in.refCnt());
      assertEquals(0, in.readableBytes());
    }

    // TODO(sergiitk): parametrize to account for other states of the tail when we need to merge
    @Test
    public void manualMerge() {
      // Create tail with no writable bytes left.
      ByteBuf tail = alloc.buffer(5, 5).setIndex(3, 5);
      tail.setCharSequence(0, "xxx01", US_ASCII);
      composite.addComponent(true, tail);

      // Input has 5 readable bytes left.
      in = alloc.buffer(10, 10).setIndex(5, 10);
      in.setCharSequence(0, "xxxxx23456", US_ASCII);
      int totalBytes =  tail.readableBytes() + in.readableBytes();

      // The tail and input together are below the threshold.
      cumulator = new NettyAdaptiveCumulator(11);
      ByteBuf component = cumulator.mergeIfNeeded(alloc, composite, in);
      // Composite buf must only contain the initial data.
      assertEquals(1, composite.numComponents());

      // A new buffer is returned, it must be neither the tail, nor the input buf.
      assertNotSame(tail, component);
      assertNotEquals(tail, component);
      assertNotSame(in, component);
      assertNotEquals(in, component);

      // Tail buf must be released
      assertEquals(0, tail.refCnt());

      // Read (discardable) bytes of the tail and the input must be discarded.
      // Readable parts of the tail and the input must be merged.
      assertEquals("0123456", component.toString(US_ASCII));
      assertEquals(alloc.calculateNewCapacity(totalBytes, Integer.MAX_VALUE), component.capacity());
      assertEquals(Integer.MAX_VALUE, component.maxCapacity());
      assertEquals(0, component.readerIndex());
      assertEquals(totalBytes, component.writerIndex());
      assertEquals(1, component.refCnt());

      // Input buf must be released and have no readable bytes.
      assertEquals(0, in.refCnt());
      assertEquals(0, in.readableBytes());
    }
  }
}
