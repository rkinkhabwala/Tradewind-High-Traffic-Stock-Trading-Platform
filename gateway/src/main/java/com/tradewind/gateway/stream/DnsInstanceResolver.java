package com.tradewind.gateway.stream;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * One instance per address the host resolves to. The JVM's positive DNS cache is shortened at startup
 * (GatewayApplication), so scaled-up and removed containers are seen within one refresh.
 */
class DnsInstanceResolver implements InstanceResolver {

    private final StreamDiscoveryProperties properties;

    DnsInstanceResolver(StreamDiscoveryProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<URI> resolve() throws Exception {
        return Arrays.stream(InetAddress.getAllByName(properties.host()))
                .map(address -> URI.create("http://" + hostLiteral(address) + ":" + properties.port()))
                .distinct()
                .toList();
    }

    private static String hostLiteral(InetAddress address) {
        String host = address.getHostAddress();
        return host.contains(":") ? "[" + host + "]" : host;
    }
}
