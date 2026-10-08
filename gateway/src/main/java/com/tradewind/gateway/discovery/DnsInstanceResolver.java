package com.tradewind.gateway.discovery;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * One instance per address the host resolves to. The JVM's positive DNS cache is shortened at startup
 * (GatewayApplication), so scaled-up and removed containers are seen within one refresh.
 */
class DnsInstanceResolver implements InstanceResolver {

    @Override
    public List<URI> resolve(String serviceId, DiscoveryProperties.Target target) throws Exception {
        return Arrays.stream(InetAddress.getAllByName(target.host()))
                .map(address -> URI.create("http://" + hostLiteral(address) + ":" + target.port()))
                .distinct()
                .toList();
    }

    private static String hostLiteral(InetAddress address) {
        String host = address.getHostAddress();
        return host.contains(":") ? "[" + host + "]" : host;
    }
}
