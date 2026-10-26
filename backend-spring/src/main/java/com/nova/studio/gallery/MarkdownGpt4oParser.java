package com.nova.studio.gallery;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * WIN-42 (T15, ADR-40) — markdown-gpt4o 解析器（Java 移植自前端
 * {@code parseMarkdownGpt4o}）：按 ### 切块，提取
 * {@code - **提示词文本：** `...`} 内联代码。
 */
public class MarkdownGpt4oParser implements PromptParser {

    private static final Pattern HEADING3 = Pattern.compile("^###\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern PROMPT_RE = Pattern.compile("- \\*\\*提示词文本：\\*\\*\\s*`([\\s\\S]*?)`");

    @Override
    public String type() {
        return "markdown-gpt4o";
    }

    @Override
    public List<ParsedPrompt> parse(PromptDataSource source, String raw, PromptSourceFetcher fetcher) {
        List<ParsedPrompt> prompts = new ArrayList<>();
        for (String block : GalleryMarkdown.splitBeforeHeading(raw, "### ")) {
            String title = GalleryMarkdown.firstMatch(block, HEADING3).trim();
            String prompt = GalleryMarkdown.firstMatch(block, PROMPT_RE);
            if (title.isEmpty() || prompt.isEmpty()) {
                continue;
            }
            int idx = prompts.size();
            prompts.add(new ParsedPrompt(
                    source.name() + "-" + idx,
                    source.name(),
                    source.sourceUrl(),
                    title,
                    prompt.trim(),
                    GalleryMarkdown.extractMarkdownImages(source, block),
                    List.of("gpt4o"),
                    GalleryCategory.CATEGORY_GPT4O,
                    "",
                    "",
                    block));
        }
        return prompts;
    }
}
