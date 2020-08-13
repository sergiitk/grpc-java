package io.grpc.internal;

public class ManagedChannelImplBuilder extends AbstractManagedChannelImplBuilder<ManagedChannelImplBuilder> {
    private final TransportFactoryBuilder transportFactoryBuilder;

    public interface TransportFactoryBuilder {
        ClientTransportFactory buildTransportFactory();
    }

    protected ManagedChannelImplBuilder(String target,
                                        TransportFactoryBuilder transportFactoryBuilder) {
        super(target);
        this.transportFactoryBuilder = transportFactoryBuilder;
    }

    public static ManagedChannelImplBuilder forTarget(String target,
                                                      TransportFactoryBuilder transportFactoryBuilder) {
        return new ManagedChannelImplBuilder(target, transportFactoryBuilder);
    }

    @Override
    protected ClientTransportFactory buildTransportFactory() {
        return transportFactoryBuilder.buildTransportFactory();
    }
}