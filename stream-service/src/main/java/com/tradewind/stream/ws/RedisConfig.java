package com.tradewind.stream.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

@Configuration
class RedisConfig {

    /** One pub/sub connection shared by all user-channel subscriptions of this instance. */
    @Bean(destroyMethod = "destroy")
    ReactiveRedisMessageListenerContainer redisListenerContainer(ReactiveRedisConnectionFactory redis) {
        return new ReactiveRedisMessageListenerContainer(redis);
    }
}
