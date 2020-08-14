package io.grpc.internal;

import java.util.concurrent.Callable;

public class ManagedChannelImplBuilder extends AbstractManagedChannelImplBuilder<ManagedChannelImplBuilder> {
    private final Callable<ClientTransportFactory> transportFactoryBuilder;
    private int defaultPort;

    protected ManagedChannelImplBuilder(String target,
                                        Callable<ClientTransportFactory> transportFactoryBuilder) {
        super(target);
        this.transportFactoryBuilder = transportFactoryBuilder;
        this.defaultPort = super.getDefaultPort();
    }

    public static ManagedChannelImplBuilder forTarget(String target,
                                                      Callable<ClientTransportFactory> transportFactoryBuilder) {
        return new ManagedChannelImplBuilder(target, transportFactoryBuilder);
    }

    @Override
    protected ClientTransportFactory buildTransportFactory() {
        try {
            return transportFactoryBuilder.call();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // @todo: handle differently?
        return null;
    }

    @Override
    public int getDefaultPort() {
        return defaultPort;
    }

    public void setDefaultPort(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    public int maxInboundMessageSize() {
        return super.maxInboundMessageSize();
    }
}
