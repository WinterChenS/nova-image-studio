package com.nova.studio.config;

import com.openai.client.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Spring AI OpenAI wiring (T0.2 spike).
 *
 * <p>For the spike the base URL / API key come from configuration
 * ({@code nova.ai.openai.base-url} / {@code nova.ai.openai.api-key}) so the
 * models can be pointed at a mock upstream in tests or a real endpoint in the
 * verification run. In M1 the credentials move to the DB-backed model registry
 * and these beans are built per model (ADR-8).
 */
@Configuration
@ConditionalOnProperty(name = "nova.ai.openai.enabled", havingValue = "true", matchIfMissing = false)
public class OpenAiAiConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAiConfig.class);

    @Bean
    public OpenAIClient openAiClient(@Value("${nova.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
                                     @Value("${nova.ai.openai.api-key:}") String apiKey) {
        OpenAIClient client = OpenAiSetup.setupSyncClient(baseUrl, apiKey, null, null, null, null,
                false, false, null, Duration.ofSeconds(30), 2, null, null,
                io.micrometer.observation.ObservationRegistry.NOOP, null, List.of());
        log.info("[spring-ai] OpenAIClient created baseUrl={}", baseUrl);
        return client;
    }

    @Bean
    public ChatModel openAiChatModel(OpenAIClient openAiClient,
                                     @Value("${nova.ai.openai.chat-model:gpt-4o-mini}") String model) {
        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .options(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    @Bean
    public ImageModel openAiImageModel(OpenAIClient openAiClient,
                                       @Value("${nova.ai.openai.image-model:gpt-image-1}") String model) {
        return OpenAiImageModel.builder()
                .openAiClient(openAiClient)
                .options(OpenAiImageOptions.builder().model(model).build())
                .build();
    }
}
