package com.tradewind.gateway.stream;

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
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The healthy stream-service instances. Every refresh interval: resolve the instances, probe each one's health
 * endpoint (with a timeout), and keep those answering 2xx. stream-service reports DOWN until it consumes ticks, so a
 * starting instance gets no clients before it can serve them. Routing picks among these round-robin.
 */
public class StreamInstances implements SmartLifecycle {

    public static final String SERVICE_ID = "stream-service";
    private static final Logger log = LoggerFactory.getLogger(StreamInstances.class);

    private final InstanceResolver resolver;
    private final StreamDiscoveryProperties properties;
    private final WebClient http;
    private final AtomicReference<List<ServiceInstance>> healthy = new AtomicReference<>(List.of());
    private volatile Disposable refreshes;

    public StreamInstances(InstanceResolver resolver, StreamDiscoveryProperties properties,
                           WebClient.Builder http, MeterRegistry meters) {
        this.resolver = resolver;
        this.properties = properties;
        this.http = http.build();
        Gauge.builder("gateway.stream.instances.healthy", healthy, h -> h.get().size()).register(meters);
    }

    public List<ServiceInstance> healthy() {
        return healthy.get();
    }

    /** One discovery round: resolve, probe in parallel, publish the healthy set. */
    Mono<List<ServiceInstance>> refresh() {
        return Mono.fromCallable(resolver::resolve)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Could not resolve {}: {}", properties.host(), e.toString());
                    return Mono.just(List.of());
                })
                .flatMapMany(Flux::fromIterable)
                .flatMap(uri -> probe(uri).filter(Boolean::booleanValue).map(ok -> instance(uri)))
                .collectSortedList((a, b) -> a.getInstanceId().compareTo(b.getInstanceId()))
                .doOnNext(this::publish);
    }

    private Mono<Boolean> probe(URI base) {
        return http.get().uri(base.resolve(properties.healthPath()))
                .retrieve().toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .timeout(properties.healthTimeout())
                .onErrorReturn(false);
    }

    private static ServiceInstance instance(URI base) {
        return new DefaultServiceInstance(base.getAuthority(), SERVICE_ID, base.getHost(), base.getPort(), false);
    }

    private void publish(List<ServiceInstance> now) {
        List<ServiceInstance> before = healthy.getAndSet(now);
        List<String> was = before.stream().map(ServiceInstance::getInstanceId).toList();
        List<String> is = now.stream().map(ServiceInstance::getInstanceId).toList();
        if (!Objects.equals(was, is)) {
            log.info("Healthy stream-service instances: {} (was {})", is, was);
        }
    }

    @Override
    public void start() {
        // First round synchronously, so the gateway does not answer 503 for /ws right after starting.
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
