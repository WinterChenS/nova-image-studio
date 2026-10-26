package com.nova.studio.gallery;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * WIN-42 (T15, ADR-40) — davidwu-json 解析器（Java 移植自前端
 * {@code parseDavidWuJson}）：数组条目 {title_cn, title_en, prompt, image, category_cn,
 * category, author, source, needs_ref, note}。
 */
public class DavidWuJsonParser implements PromptParser {

    private final ObjectMapper objectMapper;

    public DavidWuJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "davidwu-json";
    }

    @Override
    public List<ParsedPrompt> parse(PromptDataSource source, String raw, PromptSourceFetcher fetcher)
            throws Exception {
        List<ParsedPrompt> prompts = new ArrayList<>();
        JsonNode json = objectMapper.readTree(raw);
        if (!json.isArray()) {
            return prompts;
        }
        int idx = 0;
        for (JsonNode item : json) {
            String titleCn = item.path("title_cn").asText("");
            String titleEn = item.path("title_en").asText("");
            String title = !titleCn.isBlank() ? titleCn.trim() : titleEn.trim();
            if (title.isBlank()) {
                idx++;
                continue;
            }
            String prompt = item.path("prompt").asText("").trim();
            if (prompt.isBlank()) {
                idx++;
                continue;
            }
            String image = source.absoluteImage(item.path("image").asText(""));
            List<String> tags = new ArrayList<>();
            String categoryCn = item.path("category_cn").asText("");
            String category = item.path("category").asText("");
            String author = item.path("author").asText("");
            String src = item.path("source").asText("");
            String note = item.path("note").asText("");
            if (!categoryCn.isBlank()) {
                tags.add(categoryCn);
            }
            if (!category.isBlank()) {
                tags.add(category);
            }
            if (!author.isBlank()) {
                tags.add(author);
            }
            if (!src.isBlank()) {
                tags.add(src);
            }
            if (item.path("needs_ref").asBoolean(false)) {
                tags.add("需要参考图");
            }
            String inferred = GalleryCategory.infer(title, prompt, tags);
            prompts.add(new ParsedPrompt(
                    source.name() + "-" + (item.path("id").asText("").isBlank() ? idx : item.path("id").asText()),
                    source.name(),
                    source.sourceUrl(),
                    title,
                    prompt,
                    image.isBlank() ? List.of() : List.of(image),
                    tags.stream().filter(t -> !t.isBlank()).toList(),
                    inferred,
                    author,
                    note,
                    item.toString()));
            idx++;
        }
        return prompts;
    }
}
