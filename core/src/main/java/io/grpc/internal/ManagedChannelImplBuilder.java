package io.grpc.internal;

// we won't be able to override checkAuthority anymore - bury it here
public class ManagedChannelImplBuilder extends AbstractManagedChannelImplBuilder<ManagedChannelImplBuilder> {
    public interface ClientTransportFactoryFactory {
        ClientTransportFactory buildTransportFactory();
    }

    private final ClientTransportFactoryFactory clientTransportFactoryFactory;
    private int defaultPort;

    // one more for SocketAddress
    protected ManagedChannelImplBuilder(String target,
                                        ClientTransportFactoryFactory clientTransportFactoryFactory) {
        super(target);
        this.clientTransportFactoryFactory = clientTransportFactoryFactory;
        this.defaultPort = super.getDefaultPort();
    }

    @Override
    protected ClientTransportFactory buildTransportFactory() {
        // set the port
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
