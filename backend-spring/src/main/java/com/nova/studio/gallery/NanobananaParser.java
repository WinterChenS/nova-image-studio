package com.nova.studio.gallery;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * WIN-42 (T15, ADR-40) — nanobanana JSON 解析器（Java 移植自前端
 * {@code parseNanobanana}）：{sections:[{id, title, prompts:[PromptGalleryItem]}]}。
 * uniqueKey = {@code source.name-section.id-prompt.id-sectionIdx-promptIdx}。
 */
public class NanobananaParser implements PromptParser {

    private final ObjectMapper objectMapper;

    public NanobananaParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "nanobanana";
    }

    @Override
    public List<ParsedPrompt> parse(PromptDataSource source, String raw, PromptSourceFetcher fetcher)
            throws Exception {
        List<ParsedPrompt> results = new ArrayList<>();
        JsonNode data = objectMapper.readTree(raw);
        JsonNode sections = data.path("sections");
        if (!sections.isArray()) {
            return results;
        }
        int sectionIdx = 0;
        for (JsonNode section : sections) {
            String sectionId = section.path("id").asText("");
            JsonNode prompts = section.path("prompts");
            int promptIdx = 0;
            if (prompts.isArray()) {
                for (JsonNode prompt : prompts) {
                    String title = prompt.path("title").asText("");
                    String content = prompt.path("content").asText("");
                    String id = prompt.path("id").asText("");
                    List<String> tags = textList(prompt.get("tags"));
                    String category = GalleryCategory.infer(title, content, tags);
                    results.add(new ParsedPrompt(
                            source.name() + "-" + sectionId + "-" + id + "-" + sectionIdx + "-" + promptIdx,
                            source.name(),
                            source.sourceUrl(),
                            title,
                            content,
                            textList(prompt.get("images")),
                            tags,
                            category,
                            prompt.path("contributor").asText(""),
                            prompt.path("notes").asText(""),
                            prompt.toString()));
                    promptIdx++;
                }
            }
            sectionIdx++;
        }
        return results;
    }

    static List<String> textList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
        }
        return result;
    }
}
