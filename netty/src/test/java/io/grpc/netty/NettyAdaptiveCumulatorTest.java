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
import static com.google.common.truth.TruthJUnit.assume;
import static io.netty.util.CharsetUtil.US_ASCII;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.Collection;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public class NettyAdaptiveCumulatorTest {
  // Represent data as immutable ASCII Strings for easy and readable ByteBuf equality assertions.
  private static final String DATA_INITIAL = "0123";
  private static final String DATA_INCOMING = "456789";
  private static final String DATA_CUMULATED = "0123456789";

  private static Collection<Object[]> cartesianProductParams(List<?>... lists) {
    return Lists.transform(Lists.cartesianProduct(lists), new Function<List<Object>, Object[]>() {
      @Override public Object[] apply(List<Object> input) {
        return input.toArray();
      }
    });
  }

  @RunWith(JUnit4.class)
  public static class CumulateTests {
    private static final ByteBufAllocator alloc = new UnpooledByteBufAllocator(false);
    private NettyAdaptiveCumulator cumulator;

    // Buffers for testing
    private ByteBuf contiguous = ByteBufUtil.writeAscii(alloc, DATA_INITIAL);
    private CompositeByteBuf composite = alloc.compositeBuffer().addComponent(true, contiguous);
    private ByteBuf in = ByteBufUtil.writeAscii(alloc, DATA_INCOMING);

    @Before
    public void setUp() {
      cumulator = new NettyAdaptiveCumulator(0) {
        @Override
        void addInput(ByteBufAllocator alloc, CompositeByteBuf composite, ByteBuf in) {
          // To limit the testing scope to NettyAdaptiveCumulator.cumulate(), always compose
          composite.addFlattenedComponents(true, in);
        }
      };
    }

    @Test
    public void cumulate_notReadableCumulation_replacedWithInputAndReleased() {
      contiguous.readerIndex(contiguous.writerIndex());
      assertFalse(contiguous.isReadable());
      ByteBuf cumulation = cumulator.cumulate(alloc, contiguous, in);
      assertEquals(DATA_INCOMING, cumulation.toString(US_ASCII));
      assertEquals(0, contiguous.refCnt());
    }

    @Test
    public void cumulate_contiguousCumulation_newCompositeFromContiguousAndInput() {
      CompositeByteBuf cumulation = (CompositeByteBuf) cumulator.cumulate(alloc, contiguous, in);
      assertEquals(DATA_INITIAL, cumulation.component(0).toString(US_ASCII));
      assertEquals(DATA_INCOMING, cumulation.component(1).toString(US_ASCII));
      assertEquals(DATA_CUMULATED, cumulation.toString(US_ASCII));
    }

    @Test
    public void cumulate_compositeCumulation_inputAppendedAsANewComponent() {
      assertSame(composite, cumulator.cumulate(alloc, composite, in));
      assertEquals(DATA_INITIAL, composite.component(0).toString(US_ASCII));
      assertEquals(DATA_INCOMING, composite.component(1).toString(US_ASCII));
      assertEquals(DATA_CUMULATED, composite.toString(US_ASCII));
    }

    @Test
    public void cumulate_compositeCumulation_inputReleasedOnError() {
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
    public void cumulate_contiguousCumulation_inputAndNewCompositeReleasedOnError() {
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
      ByteBufAllocator mockAlloc = mock(ByteBufAllocator.class);
      when(mockAlloc.compositeBuffer(anyInt())).thenReturn(composite);
      try {
        cumulator.cumulate(mockAlloc, contiguous, in);
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

  @RunWith(Parameterized.class)
  public static class ShouldComposeTests {
    @Parameters(name = "composeMinSize={0}, tailData=\"{1}\", inData=\"{2}\"")
    public static Collection<Object[]> params() {
      List<?> composeMinSize = ImmutableList.of(0, 9, 10, 11, Integer.MAX_VALUE);
      List<?> tailData = ImmutableList.of("", DATA_INITIAL);
      List<?> inData = ImmutableList.of("", DATA_INCOMING);
      return cartesianProductParams(composeMinSize, tailData, inData);
    }

    @Parameter(0) public int composeMinSize;
    @Parameter(1) public String tailData;
    @Parameter(2) public String inData;

    private CompositeByteBuf composite;
    private ByteBuf tail;
    private ByteBuf in;

    @Before
    public void setUp() {
      ByteBufAllocator alloc = new UnpooledByteBufAllocator(false);
      in = ByteBufUtil.writeAscii(alloc, inData);
      tail = ByteBufUtil.writeAscii(alloc, tailData);
      composite = alloc.compositeBuffer(Integer.MAX_VALUE);
      // Note that addFlattenedComponents() will not add a new component when tail is not readable.
      composite.addFlattenedComponents(true, tail);
    }

    @Test
    public void shouldCompose_emptyComposite() {
      assume().that(composite.numComponents()).isEqualTo(0);
      assertTrue(NettyAdaptiveCumulator.shouldCompose(composite, in, composeMinSize));
    }

    @Test
    public void shouldCompose_composeMinSizeReached() {
      assume().that(composite.numComponents()).isGreaterThan(0);
      assume().that(tail.readableBytes() + in.readableBytes()).isAtLeast(composeMinSize);
      assertTrue(NettyAdaptiveCumulator.shouldCompose(composite, in, composeMinSize));
    }

    @Test
    public void shouldCompose_composeMinSizeNotReached() {
      assume().that(composite.numComponents()).isGreaterThan(0);
      assume().that(tail.readableBytes() + in.readableBytes()).isLessThan(composeMinSize);
      assertFalse(NettyAdaptiveCumulator.shouldCompose(composite, in, composeMinSize));
    }
  }

  @RunWith(Parameterized.class)
  public static class MergeWithCompositeTail {
    private static final String DATA_INCOMING_DISCARDABLE = "xxxxx";
    private static final int TAIL_READER_INDEX = 1;

    @Parameters(name = "tail(initialCapacity={0}, maxCapacity={1})")
    public static Collection<Object[]> params() {
      // ByteBuf tailTemplate = ByteBufUtil.writeAscii(alloc, DATA_INITIAL);
      List<?> tailInitialCapacity = ImmutableList.of(
          // fast write: initial capacity is fully taken by the initial data
          DATA_INITIAL.length(),
          // regular write: incoming data fits into initial capacity
          DATA_CUMULATED.length()
      );
      List<?> tailMaxCapacity = ImmutableList.of(
          DATA_CUMULATED.length()
      );
      return cartesianProductParams(tailInitialCapacity, tailMaxCapacity);
    }

    @Parameter(0) public int tailInitialCapacity;
    @Parameter(1) public int tailMaxCapacity;

    // Use pooled allocator to have maxFastWritableBytes() behave differently than writableBytes().
    private final ByteBufAllocator alloc = new PooledByteBufAllocator();
    private CompositeByteBuf composite;
    private ByteBuf tail;
    private ByteBuf in;


    @Before
    public void setUp() {
      in = alloc.buffer()
          .writeBytes(DATA_INCOMING_DISCARDABLE.getBytes(US_ASCII))
          .writeBytes(DATA_INCOMING.getBytes(US_ASCII))
          .readerIndex(DATA_INCOMING_DISCARDABLE.length());
      tail = alloc.buffer(tailInitialCapacity, tailMaxCapacity)
          .writeBytes(DATA_INITIAL.getBytes(US_ASCII))
          .readerIndex(TAIL_READER_INDEX);
      composite = alloc.compositeBuffer(Integer.MAX_VALUE).addFlattenedComponents(true, tail);
      // TODO(sergiitk): test with another in composite to confirm writer index reset
      // TODO(sergiitk): test with tail partially read
    }

    @Test
    public void mergeWithCompositeTail_expandTailWrite() {
      // Incoming data fits into tail initial capacity.
      assume().that(in.readableBytes()).isAtMost(tail.writableBytes());

      NettyAdaptiveCumulator.mergeWithCompositeTail(alloc, composite, in);
      // Composite should still have a single component.
      assertEquals(1, composite.numComponents());
      // Confirm the tail hasn't been replaced
      assertSame(tail, composite.component(0).unwrap());

      // Read (discardable) bytes of the tail must stay as is.
      // Read (discardable) bytes of the input must be discarded.
      // Readable part of the input must be appended to the tail.
      assertEquals(DATA_CUMULATED.substring(TAIL_READER_INDEX),
          composite.toString(0, composite.readableBytes(), US_ASCII));
      assertEquals(DATA_CUMULATED, tail.toString(0, DATA_CUMULATED.length(), US_ASCII));
      // Capacity must not change.
      assertEquals(tailInitialCapacity, tail.capacity());
      assertEquals(TAIL_READER_INDEX, tail.readerIndex());
      assertEquals(DATA_CUMULATED.length(), tail.writerIndex());
      // Retained by the composite buf.
      assertEquals(1, tail.refCnt());

      // Incoming data buf must be fully read and released.
      assertEquals(0, in.readableBytes());
      assertEquals(0, in.refCnt());
    }

    @Test
    public void mergeWithCompositeTail_expandTailFastWrite() {
      // Incoming data does't fit into tail initial capacity.
      assume().that(in.readableBytes()).isGreaterThan(tail.writableBytes());
      // But tail can be expanded fast to fit the incoming data.
      assume().that(in.readableBytes()).isAtMost(tail.maxFastWritableBytes());

      NettyAdaptiveCumulator.mergeWithCompositeTail(alloc, composite, in);
      // Composite should still have a single component.
      assertEquals(1, composite.numComponents());
      // Confirm the tail hasn't been replaced
      assertSame(tail, composite.component(0).unwrap());

      // Read (discardable) bytes of the tail must stay as is.
      // Read (discardable) bytes of the input must be discarded.
      // Readable part of the input must be appended to the tail.
      assertEquals(DATA_CUMULATED.substring(TAIL_READER_INDEX),
          composite.toString(0, composite.readableBytes(), US_ASCII));
      assertEquals(DATA_CUMULATED, tail.toString(0, DATA_CUMULATED.length(), US_ASCII));
      // Tails's capacity is extended to fit the incoming data exactly.
      assertEquals(DATA_CUMULATED.length(), tail.capacity());
      assertEquals(TAIL_READER_INDEX, tail.readerIndex());
      assertEquals(DATA_CUMULATED.length(), tail.writerIndex());
      // Retained by the composite buf.
      assertEquals(1, tail.refCnt());

      // Incoming data buf must be fully read and released.
      assertEquals(0, in.readableBytes());
      assertEquals(0, in.refCnt());
    }

    // @Test
    // public void tailNotExpandableReplaceWithNew() {
    //
    // }

  }

  //
  //
  // @RunWith(JUnit4.class)
  // public static class MergeIfNeededTests extends AbstractNettyAdaptiveCumulatorTest {
  //
  //   @Test
  //   public void expandTail_reallocate() {
  //     // Use pooled allocator to test for maxFastWritableBytes() being different from writableBytes().
  //     alloc = new PooledByteBufAllocator();
  //
  //     // Create tail with no writable bytes left, but allow to expand it.
  //     final int tailBytes = 5;
  //     ByteBuf tail = alloc.buffer(tailBytes).setIndex(3, tailBytes);
  //     tail.setCharSequence(0, "01234", US_ASCII);
  //     composite.addComponent(true, tail);
  //     final int tailFastCapacity = tail.writerIndex() + tail.maxFastWritableBytes();
  //
  //     // Make input larger than tailFastCapacity
  //     in = alloc.buffer(tailFastCapacity + 1).writeZero(tailFastCapacity).writeByte(1);
  //     byte[] expectedInput = new byte[tailFastCapacity + 1];
  //     in.getBytes(0, expectedInput);
  //
  //     // Force merge.
  //     cumulator = new NettyAdaptiveCumulator(Integer.MAX_VALUE);
  //     ByteBuf component = cumulator.mergeIfNeeded(alloc, composite, in);
  //     // Composite buf must only contain the initial data.
  //     assertEquals(1, composite.numComponents());
  //
  //     // Modified tail is returned, but it must be the same object.
  //     assertSame(tail, component);
  //     // Read (discardable) bytes of the tail must stay as is.
  //     assertEquals("01234", tail.toString(0, 5, US_ASCII));
  //
  //     // Ensure the input is appended.
  //     byte[] appendedInput = new byte[tailFastCapacity + 1];
  //     component.getBytes(tailBytes, appendedInput);
  //     assertArrayEquals(expectedInput, appendedInput);
  //     int totalBytes = tailBytes + tailFastCapacity + 1;
  //     assertEquals(alloc.calculateNewCapacity(totalBytes, Integer.MAX_VALUE), component.capacity());
  //     assertEquals(totalBytes, component.writerIndex());
  //     assertEquals(3, component.readerIndex());
  //     assertEquals(1, component.refCnt());
  //
  //     // Input buf must be released and have no readable bytes.
  //     assertEquals(0, in.refCnt());
  //     assertEquals(0, in.readableBytes());
  //   }
  //
  //   // TODO(sergiitk): parametrize to account for other states of the tail when we need to merge
  //   @Test
  //   public void manualMerge() {
  //     // Create tail with no writable bytes left.
  //     ByteBuf tail = alloc.buffer(5, 5).setIndex(3, 5);
  //     tail.setCharSequence(0, "xxx01", US_ASCII);
  //     composite.addComponent(true, tail);
  //
  //     // Input has 5 readable bytes left.
  //     in = alloc.buffer(10, 10).setIndex(5, 10);
  //     in.setCharSequence(0, "xxxxx23456", US_ASCII);
  //     int totalBytes =  tail.readableBytes() + in.readableBytes();
  //
  //     // The tail and input together are below the threshold.
  //     cumulator = new NettyAdaptiveCumulator(11);
  //     ByteBuf component = cumulator.mergeIfNeeded(alloc, composite, in);
  //     // Composite buf must only contain the initial data.
  //     assertEquals(1, composite.numComponents());
  //
  //     // A new buffer is returned, it must be neither the tail, nor the input buf.
  //     assertNotSame(tail, component);
  //     assertNotEquals(tail, component);
  //     assertNotSame(in, component);
  //     assertNotEquals(in, component);
  //
  //     // Tail buf must be released
  //     assertEquals(0, tail.refCnt());
  //
  //     // Read (discardable) bytes of the tail and the input must be discarded.
  //     // Readable parts of the tail and the input must be merged.
  //     assertEquals("0123456", component.toString(US_ASCII));
  //     assertEquals(alloc.calculateNewCapacity(totalBytes, Integer.MAX_VALUE), component.capacity());
  //     assertEquals(Integer.MAX_VALUE, component.maxCapacity());
  //     assertEquals(0, component.readerIndex());
  //     assertEquals(totalBytes, component.writerIndex());
  //     assertEquals(1, component.refCnt());
  //
  //     // Input buf must be released and have no readable bytes.
  //     assertEquals(0, in.refCnt());
  //     assertEquals(0, in.readableBytes());
  //   }
  // }

  // @Test
  // public void shouldNotMerge_tail() {
  //   assume().that(composite.numComponents()).isEqualTo(0);
  //   assertThat(NettyAdaptiveCumulator.shouldCompose(composite, in, composeMinSize)).isTrue();
  // }

  // @Test
  // public void shouldMerge_emptyComposite() {
  //   assume().that(composite.isReadable()).isFalse();
  //
  //   cumulator.addInput(alloc, composite, in);
  //   assertEquals(inData, composite.toString(US_ASCII));
  //   assertEquals(inData.length() > 0 ? 1 : 0, composite.numComponents());
  // }



  // private static ByteBufAllocator alloc = new UnpooledByteBufAllocator(false);

  // protected ByteBuf contiguous = ByteBufUtil.writeAscii(alloc, DATA_INITIAL);
  // protected CompositeByteBuf composite = alloc.compositeBuffer().addComponent(true, contiguous);
  // protected ByteBuf in = ByteBufUtil.writeAscii(alloc, DATA_INCOMING);

  // @DataPoint public static CompositeByteBuf COMPOSITE_EMPTY = alloc.compositeBuffer();
  // @DataPoint public static CompositeByteBuf COMPOSITE_NOT_READABLE = alloc.compositeBuffer() .w
}
