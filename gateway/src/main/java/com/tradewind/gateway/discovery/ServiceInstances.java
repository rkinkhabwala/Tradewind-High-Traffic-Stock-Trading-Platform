package com.tradewind.gateway.discovery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The healthy instances of each discovered service. Every refresh interval, per service: resolve the instances,
 * probe each one's health endpoint (with a timeout) and keep those answering 2xx. stream-service reports DOWN until
 * it consumes ticks, so a starting instance gets no clients before it can serve them. Routing picks among these
 * round-robin; a service with no healthy instance gets 503.
 */
public class ServiceInstances implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ServiceInstances.class);

    private final InstanceResolver resolver;
    private final DiscoveryProperties properties;
    private final WebClient http;
    private final Map<String, AtomicReference<List<ServiceInstance>>> healthy = new ConcurrentHashMap<>();
    private volatile Disposable refreshes;

    public ServiceInstances(InstanceResolver resolver, DiscoveryProperties properties, WebClient.Builder http,
                            MeterRegistry meters) {
        this.resolver = resolver;
        this.properties = properties;
        this.http = http.build();
        properties.services().keySet().forEach(id -> {
            AtomicReference<List<ServiceInstance>> current = new AtomicReference<>(List.of());
            healthy.put(id, current);
            Gauge.builder("gateway.instances.healthy", current, c -> c.get().size()).tag("service", id).register(meters);
        });
    }

    public List<ServiceInstance> healthy(String serviceId) {
        AtomicReference<List<ServiceInstance>> current = healthy.get(serviceId);
        return current == null ? List.of() : current.get();
    }

    /** One discovery round for every service. */
    Mono<Void> refresh() {
        return Flux.fromIterable(properties.services().entrySet())
                .flatMap(e -> refresh(e.getKey(), e.getValue()))
                .then();
    }

    private Mono<List<ServiceInstance>> refresh(String serviceId, DiscoveryProperties.Target target) {
        return Mono.fromCallable(() -> resolver.resolve(serviceId, target))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Could not resolve {} ({}): {}", serviceId, target.host(), e.toString());
                    return Mono.just(List.of());
                })
                .flatMapMany(Flux::fromIterable)
                .flatMap(uri -> probe(uri, target).filter(Boolean::booleanValue).map(ok -> instance(serviceId, uri)))
                .collectSortedList((a, b) -> a.getInstanceId().compareTo(b.getInstanceId()))
                .doOnNext(now -> publish(serviceId, now));
    }

    private Mono<Boolean> probe(URI base, DiscoveryProperties.Target target) {
        return http.get().uri(base.resolve(target.healthPath()))
                .retrieve().toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .timeout(properties.healthTimeout())
                .onErrorReturn(false);
    }

    private static ServiceInstance instance(String serviceId, URI base) {
        return new DefaultServiceInstance(base.getAuthority(), serviceId, base.getHost(), base.getPort(), false);
    }

    private void publish(String serviceId, List<ServiceInstance> now) {
        List<ServiceInstance> before = healthy.get(serviceId).getAndSet(now);
        List<String> was = before.stream().map(ServiceInstance::getInstanceId).toList();
        List<String> is = now.stream().map(ServiceInstance::getInstanceId).toList();
        if (!Objects.equals(was, is)) {
            log.info("Healthy {} instances: {} (was {})", serviceId, is, was);
        }
    }

    @Override
    public void start() {
        // First round synchronously, so the gateway does not answer 503 right after starting.
        refresh().block(properties.healthTimeout().plusSeconds(2));
        refreshes = Flux.interval(properties.refreshInterval(), properties.refreshInterval())
                .onBackpressureDrop()
                .concatMap(tick -> refresh(), 1)
                .subscribe();
    }

    @Override
    public void stop() {
        Disposable d = refreshes;
        if (d != null) {
            d.dispose();
        }
        refreshes = null;
    }

    @Override
    public boolean isRunning() {
        return refreshes != null;
    }
}
