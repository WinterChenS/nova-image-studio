package com.nova.studio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Shared {@link WebClient.Builder} for the custom upstream clients
 * (partial_images / Gemini / Grok image clients and the text proxy). Boot's
 * reactive auto-configuration does not register a builder when Spring MVC is
 * the primary web stack, so it is defined here explicitly.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
