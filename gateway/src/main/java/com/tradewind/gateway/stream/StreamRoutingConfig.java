package com.tradewind.gateway.stream;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * The /ws route targets {@code lb://stream-service}: Spring Cloud LoadBalancer's round-robin picks among the
 * instances {@link StreamInstances} currently considers healthy (503 if there are none).
 */
@Configuration
@LoadBalancerClient(name = StreamInstances.SERVICE_ID, configuration = StreamRoutingConfig.Supplier.class)
class StreamRoutingConfig {

    @Bean
    @ConditionalOnMissingBean
    InstanceResolver instanceResolver(StreamDiscoveryProperties properties) {
        return new DnsInstanceResolver(properties);
    }

    @Bean
    StreamInstances streamInstances(InstanceResolver resolver, StreamDiscoveryProperties properties,
                                    WebClient.Builder http, MeterRegistry meters) {
        return new StreamInstances(resolver, properties, http, meters);
    }

    /** Lives in the load balancer's per-service child context; StreamInstances comes from the parent. */
    static class Supplier {

        @Bean
        ServiceInstanceListSupplier streamInstanceSupplier(StreamInstances instances) {
            return new ServiceInstanceListSupplier() {
                @Override
                public String getServiceId() {
                    return StreamInstances.SERVICE_ID;
                }

                @Override
                public Flux<List<ServiceInstance>> get() {
                    return Flux.defer(() -> Flux.just(instances.healthy()));
                }
            };
        }
    }
}
