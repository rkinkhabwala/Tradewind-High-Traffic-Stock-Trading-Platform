package com.tradewind.gateway.stream;

import java.net.URI;
import java.util.List;

/** Finds the base URIs of the stream-service instances that currently exist (healthy or not). */
public interface InstanceResolver {

    List<URI> resolve() throws Exception;
}
