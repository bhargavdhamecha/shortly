package com.custom.url.shortner.config;

import com.custom.url.shortner.service.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    private final EurekaConfig eurekaConfig;

    // Spring automatically injects your Eureka configuration interface here
    public AppConfig(EurekaConfig eurekaConfig) {
        this.eurekaConfig = eurekaConfig;
    }

    /**
     * Declares the Snowflake Generator as a Managed Spring Bean.
     * By default, this is a Singleton, meaning all threads in your
     * application will share this exact, synchronized generation instance.
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(eurekaConfig);
    }
}
