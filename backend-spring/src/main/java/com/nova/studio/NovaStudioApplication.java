package com.nova.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Nova Image Studio backend — Spring Boot (M1 P0 backend core, WIN-11).
 *
 * <p>Version baseline: Spring Boot 4.1.0 + Spring AI 2.0.0 + JDK 21 (ADR-1,
 * locked by the M0 spike). {@code @EnableScheduling} powers the TTL cleanup
 * and rate-bucket sweep (Node setInterval equivalents).
 */
@SpringBootApplication
@EnableScheduling
public class NovaStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovaStudioApplication.class, args);
    }
}
