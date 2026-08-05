package com.nova.studio.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.nova.studio.infra.UuidTypeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * WIN-16 (ADR-11) — MyBatis-Plus configuration glue: registers the
 * {@link UuidTypeHandler} for all {@link UUID} parameters/results (MyBatis
 * has no built-in UUID handler). Everything else uses MyBatis-Plus defaults.
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public ConfigurationCustomizer uuidTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(UUID.class, new UuidTypeHandler());
    }
}
