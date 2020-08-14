package io.grpc.internal;


public class ManagedChannelImplBuilder extends AbstractManagedChannelImplBuilder<ManagedChannelImplBuilder> {
    public interface ClientTransportFactoryFactory {
        ClientTransportFactory buildTransportFactory();
    }

    private final ClientTransportFactoryFactory clientTransportFactoryFactory;
    private int defaultPort;

    protected ManagedChannelImplBuilder(String target,
                                        ClientTransportFactoryFactory clientTransportFactoryFactory) {
        super(target);
        this.clientTransportFactoryFactory = clientTransportFactoryFactory;
        this.defaultPort = super.getDefaultPort();
    }

    public static ManagedChannelImplBuilder forTarget(String target,
                                                      ClientTransportFactoryFactory transportFactoryBuilder) {
        return new ManagedChannelImplBuilder(target, transportFactoryBuilder);
    }

    @Override
    protected ClientTransportFactory buildTransportFactory() {
        return clientTransportFactoryFactory.buildTransportFactory();
    }

    @Override
    public int getDefaultPort() {
        return defaultPort;
    }

    public void setDefaultPort(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    @Override
    public int maxInboundMessageSize() {
        return super.maxInboundMessageSize();
    }
}
