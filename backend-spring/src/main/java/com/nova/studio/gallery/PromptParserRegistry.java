package com.nova.studio.gallery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WIN-42 (T15, ADR-40) — 提示广场解析器注册表（A4：Java 移植）：6 种格式
 * （nanobanana / gpt-image-2 / markdown-awesome / markdown-gpt4o /
 * markdown-youmind / davidwu-json）按 type 注册与分发。
 */
@Component
public class PromptParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptParserRegistry.class);

    private final Map<String, PromptParser> parsers = new LinkedHashMap<>();

    public PromptParserRegistry(ObjectMapper objectMapper) {
        register(new NanobananaParser(objectMapper));
        register(new GptImage2Parser(objectMapper));
        register(new MarkdownAwesomeParser());
        register(new MarkdownGpt4oParser());
        register(new MarkdownYouMindParser());
        register(new DavidWuJsonParser(objectMapper));
    }

    public void register(PromptParser parser) {
        parsers.put(parser.type(), parser);
    }

    public PromptParser get(String type) {
        return parsers.get(type);
    }

    public Map<String, PromptParser> all() {
        return parsers;
    }
}
