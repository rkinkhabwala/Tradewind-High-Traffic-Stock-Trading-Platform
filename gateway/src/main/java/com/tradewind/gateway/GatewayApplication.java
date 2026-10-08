package com.tradewind.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.security.Security;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        // stream-service instances come and go (scaling, crashes); the JVM caches DNS answers for 30s by default.
        Security.setProperty("networkaddress.cache.ttl", "1");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        SpringApplication.run(GatewayApplication.class, args);
    }
}
