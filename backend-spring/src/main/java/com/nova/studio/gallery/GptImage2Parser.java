package com.nova.studio.gallery;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WIN-42 (T15, ADR-40) — gpt-image-2 解析器（Java 移植自前端 {@code parseGptImage2}）：
 * 主 JSON（ingested_tweets.json records）+ 附加 case markdown 文件（提取 Prompt 代码块）。
 */
public class GptImage2Parser implements PromptParser {

    private static final Pattern CASE_RE = Pattern.compile(
            "### Case \\d+: \\[[^\\]]+\\]\\(([^)]+)\\).*?\\*\\*Prompt:\\*\\*\\s*\\r?\\n\\s*```[\\w-]*\\r?\\n([\\s\\S]*?)\\r?\\n```",
            Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public GptImage2Parser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "gpt-image-2";
    }

    @Override
    public List<ParsedPrompt> parse(PromptDataSource source, String raw, PromptSourceFetcher fetcher)
            throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        if (source.caseFiles() != null) {
            for (String file : source.caseFiles()) {
                try {
                    String markdown = fetcher.fetch(source.baseUrl() + "/" + file);
                    if (markdown != null && !markdown.isBlank()) {
                        collectCases(cases, markdown);
                    }
                } catch (Exception e) {
                    // 单 case 文件失败不影响其余文件
                }
            }
        }

        List<ParsedPrompt> results = new ArrayList<>();
        JsonNode data = objectMapper.readTree(raw);
        JsonNode records = data.path("records");
        if (!records.isArray()) {
            return results;
        }
        int idx = 0;
        for (JsonNode record : records) {
            String title = record.path("title").asText("");
            if (title.isBlank()) {
                idx++;
                continue;
            }
            String tweetUrl = record.path("tweet_url").asText("");
            String promptText = cases.get(tweetUrl);
            if (promptText == null || promptText.isBlank()) {
                idx++;
                continue;
            }
            String imageUrl = source.baseUrl() + "/" + record.path("image_dir").asText("") + "/output.jpg";
            List<String> tags = GalleryMarkdown.tagsFromCategory(record.path("category").asText(null));
            results.add(new ParsedPrompt(
                    source.name() + "-" + idx,
                    source.name(),
                    source.sourceUrl(),
                    title,
                    promptText,
                    imageUrl.isBlank() ? List.of() : List.of(imageUrl),
                    tags,
                    GalleryCategory.CATEGORY_GPT_IMAGE_2,
                    "",
                    "",
                    record.toString()));
            idx++;
        }
        return results;
    }

    private static void collectCases(Map<String, String> cases, String markdown) {
        Matcher matcher = CASE_RE.matcher(markdown);
        while (matcher.find()) {
            cases.put(matcher.group(1), matcher.group(2).trim());
        }
    }
}
