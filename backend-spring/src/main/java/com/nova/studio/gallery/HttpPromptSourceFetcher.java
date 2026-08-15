package com.nova.studio.gallery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * WIN-42 (T15, ADR-40) — {@link PromptSourceFetcher} 生产实现：JDK {@link HttpClient}
 * 拉取远程源内容（超时 30s；非 2xx 视为失败，由同步服务单源容错捕获）。
 */
public class HttpPromptSourceFetcher implements PromptSourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpPromptSourceFetcher.class);

    private final HttpClient client;

    public HttpPromptSourceFetcher() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public HttpPromptSourceFetcher(HttpClient client) {
        this.client = client;
    }

    @Override
    public String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "NovaImage/3.4.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("[gallery] 源拉取失败: url={}, status={}", url, response.statusCode());
            throw new IllegalStateException("远程源返回 " + response.statusCode() + ": " + url);
        }
        return response.body();
    }
}
