package com.tradewind.gateway.discovery;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Routes with {@code lb://<service>} (the /ws route and the order routes) use Spring Cloud LoadBalancer's
 * round-robin over the instances {@link ServiceInstances} currently considers healthy.
 */
@Configuration
@LoadBalancerClients(defaultConfiguration = DiscoveryRoutingConfig.Supplier.class)
class DiscoveryRoutingConfig {

    @Bean
    @ConditionalOnMissingBean
    InstanceResolver instanceResolver() {
        return new DnsInstanceResolver();
    }

    @Bean
    ServiceInstances serviceInstances(InstanceResolver resolver, DiscoveryProperties properties,
                                      WebClient.Builder http, MeterRegistry meters) {
        return new ServiceInstances(resolver, properties, http, meters);
    }

    /** Lives in each load-balanced service's child context; ServiceInstances comes from the parent. */
    static class Supplier {

        @Bean
        ServiceInstanceListSupplier discoveredInstanceSupplier(ServiceInstances instances, Environment environment) {
            String serviceId = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
            return new ServiceInstanceListSupplier() {
                @Override
                public String getServiceId() {
                    return serviceId;
                }

                @Override
                public Flux<List<ServiceInstance>> get() {
                    return Flux.defer(() -> Flux.just(instances.healthy(serviceId)));
                }
            };
        }
    }
}
