package com.tradewind.gateway.discovery;

import java.net.URI;
import java.util.List;

/** Finds the base URIs of a service's instances that currently exist (healthy or not). */
public interface InstanceResolver {

    List<URI> resolve(String serviceId, DiscoveryProperties.Target target) throws Exception;
}
