package com.tradewind.stream.ws;

import com.tradewind.stream.StreamProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@Configuration
class WebSocketConfig {

    static final String PATH = "/ws";

    @Bean
    HandlerMapping webSocketMapping(StreamWebSocketHandler handler) {
        return new SimpleUrlHandlerMapping(Map.of(PATH, handler), Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    WebSocketService webSocketService(StreamProperties properties) {
        ReactorNettyRequestUpgradeStrategy upgrade = new ReactorNettyRequestUpgradeStrategy();
        upgrade.setMaxFramePayloadLength(properties.maxFrameBytes());
        return new HandshakeWebSocketService(upgrade);
    }

    @Bean
    WebSocketHandlerAdapter webSocketHandlerAdapter(WebSocketService webSocketService) {
        return new WebSocketHandlerAdapter(webSocketService);
    }

    /** Conflation flushes and session checks. */
    @Bean(destroyMethod = "dispose")
    Scheduler streamScheduler() {
        return Schedulers.newParallel("stream", Runtime.getRuntime().availableProcessors());
    }
}
