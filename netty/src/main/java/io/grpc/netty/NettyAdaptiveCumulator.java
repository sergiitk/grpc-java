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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

final class NettyAdaptiveCumulator implements io.netty.handler.codec.ByteToMessageDecoder.Cumulator {
  private final int composeMinSize;

  public NettyAdaptiveCumulator(int composeMinSize) {
    Preconditions.checkArgument(composeMinSize >= 0, "composeMinSize must be non-negative");
    this.composeMinSize = composeMinSize;
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
    if (!cumulation.isReadable()) {
      cumulation.release();
      return in;
    }
    CompositeByteBuf composite = null;
    try {
      if (cumulation instanceof CompositeByteBuf && cumulation.refCnt() == 1) {
        composite = (CompositeByteBuf) cumulation;
        // Writer index must equal capacity if we are going to "write"
        // new components to the end
        if (composite.writerIndex() != composite.capacity()) {
          composite.capacity(composite.writerIndex());
        }
      } else {
        composite = alloc.compositeBuffer(Integer.MAX_VALUE)
            .addFlattenedComponents(true, cumulation);
      }
      in = mergeIfNeeded(alloc, composite, in);
      composite.addFlattenedComponents(true, in);
      in = null;
      return composite;
    } finally {
      if (in != null) {
        // We must release if the ownership was not transferred as otherwise it may produce a leak
        in.release();
        // Also release any new buffer allocated if we're not returning it
        if (composite != null && composite != cumulation) {
          composite.release();
        }
      }
    }
  }

  @VisibleForTesting
  ByteBuf mergeIfNeeded(ByteBufAllocator alloc, CompositeByteBuf composite, ByteBuf in) {
    int componentCount = composite.numComponents();
    if (componentCount == 0) {
      return in;
    }

    int newBytes = in.readableBytes();
    int tailIndex = composite.numComponents() - 1;
    int tailStart = composite.toByteIndex(tailIndex);
    int tailBytes = composite.capacity() - tailStart;
    int totalBytes = newBytes + tailBytes;
    if (totalBytes >= composeMinSize) {
      return in;
    }

    // The total size of the new data and the last component are below the threshold. Merge them.
    ByteBuf tail = composite.component(tailIndex);
    ByteBuf merged = null;

    // The goal is to prevent O(n^2) runtime in a pathological case, that forces copying the tail
    // component into a new buffer, for each incoming single-byte buffer.
    // We append the new bytes to the tail, when a write (or a fast write) is possible.
    // Otherwise, to achieve runtime amortization, the tail is replaced with a new buffer,
    // with capacity normalized to the closest power of two by alloc.calculateNewCapacity().
    try {
      if (tail.refCnt() == 1 && !tail.isReadOnly() && totalBytes <= tail.maxCapacity()) {
        // Ideal case: the tail isn't shared, and can be expanded to the required capacity.
        // Take ownership of the tail.
        tail = tail.retainedDuplicate().unwrap();
        composite.removeComponent(tailIndex);
        assert tail.refCnt() == 1;
        // The tail is a readable non-composite buffer, so writeBytes() handles everything for us.
        // - ensureWritable() performs a fast resize when possible (f.e. PooledByteBuf's simply
        //   updates its boundary to the end of consecutive memory run assigned to this buffer)
        // - when the required size doesn't fit into maxFastWritableBytes(), a new buffer is
        //   allocated, and the capacity calculated with alloc.calculateNewCapacity()
        merged = tail.writeBytes(in);
      } else {
        // The tail is shared, or not expandable. Replace it with a new buffer of desired capacity.
        merged = alloc.buffer(alloc.calculateNewCapacity(totalBytes, Integer.MAX_VALUE));
        merged.setBytes(0, composite, tailStart, tailBytes)
            .setBytes(tailBytes, in, newBytes)
            .writerIndex(totalBytes);
        composite.removeComponent(tailIndex);
        in.readerIndex(in.writerIndex());
      }
      return merged;
    } finally {
      in.release();
      // TODO(sergiitk): cleanup on exceptions
      // // Input buffer was merged with the tail.
      // // In case of a failed merge, release it to prevent a leak.
      // if (merged != null && merged.readableBytes() != totalBytes) {
      //   merged.release();
      // }
    }
  }
}
