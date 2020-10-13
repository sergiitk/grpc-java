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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

// TODO(sergiitk): unstable api?
public final class NettyAdaptiveCumulator implements io.netty.handler.codec.ByteToMessageDecoder.Cumulator {

  private final AdaptiveCumulatorConsolidateHeuristic heuristic;

  public interface AdaptiveCumulatorConsolidateHeuristic {
    int consolidateLast(CompositeByteBuf composite);
  }

  public static class MinLastComponentsCapacityConsolidateHeuristic implements
      AdaptiveCumulatorConsolidateHeuristic {
    private final int numComponents;
    private final int minCapacity;

    public MinLastComponentsCapacityConsolidateHeuristic(int numComponents, int minCapacity) {
      this.numComponents = numComponents;
      this.minCapacity = minCapacity;
    }

    @Override
    public int consolidateLast(CompositeByteBuf composite) {
      if (composite.numComponents() < numComponents) {
        return 0;
      }
      int startComponent = composite.numComponents() - numComponents;
      int capacity = composite.capacity() - composite.toByteIndex(startComponent);
      return capacity < minCapacity ? numComponents : 0;
    }
  }

  public NettyAdaptiveCumulator(AdaptiveCumulatorConsolidateHeuristic heuristic) {
    this.heuristic = heuristic;
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
    if (!cumulation.isReadable()) {
      cumulation.release();
      return in;
    }
    // logger.warning(in.toString());
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
      composite.addFlattenedComponents(true, in);
      int consolidateNum = this.heuristic.consolidateLast(composite);
      if (consolidateNum > 0) {
        composite.consolidate(composite.numComponents() - consolidateNum, consolidateNum);
      }
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
}
