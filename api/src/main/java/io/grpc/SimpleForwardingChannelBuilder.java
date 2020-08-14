package io.grpc;

import io.grpc.internal.AbstractManagedChannelImplBuilder;

public abstract class SimpleForwardingChannelBuilder
        <T extends ForwardingChannelBuilder<T>> extends ForwardingChannelBuilder<T> {
}
