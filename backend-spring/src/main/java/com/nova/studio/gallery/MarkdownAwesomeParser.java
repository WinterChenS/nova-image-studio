package com.nova.studio.gallery;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * WIN-42 (T15, ADR-40) — markdown-awesome 解析器（Java 移植自前端
 * {@code parseMarkdownAwesome}）：按 ## 切节、### 切块，提取
 * {@code **提示词:** ```...```} 代码块。
 */
public class MarkdownAwesomeParser implements PromptParser {

    private static final Pattern HEADING2 = Pattern.compile("^##\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern HEADING3 = Pattern.compile("^###\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern LINK_TITLE = Pattern.compile("\\[([^\\]]+)]\\([^)]+\\)");
    private static final Pattern PROMPT_RE = Pattern.compile(
            "\\*\\*提示词:\\*\\*\\s*\\r?\\n\\s*```[\\w-]*\\r?\\n([\\s\\S]*?)\\r?\\n```");

    @Override
    public String type() {
        return "markdown-awesome";
    }

    @Override
    public List<ParsedPrompt> parse(PromptDataSource source, String raw, PromptSourceFetcher fetcher) {
        List<ParsedPrompt> prompts = new ArrayList<>();
        for (String section : GalleryMarkdown.splitBeforeHeading(raw, "## ")) {
            List<String> sectionTags = GalleryMarkdown.tagsFromHeading(
                    GalleryMarkdown.firstMatch(section, HEADING2));
            for (String block : GalleryMarkdown.splitBeforeHeading(section, "### ")) {
                String title = GalleryMarkdown.firstMatch(block, HEADING3);
                title = LINK_TITLE.matcher(title).replaceAll("$1").trim();
                String prompt = GalleryMarkdown.firstMatch(block, PROMPT_RE);
                if (title.isEmpty() || prompt.isEmpty()) {
                    continue;
                }
                String category = GalleryCategory.infer(title, prompt, sectionTags);
                int idx = prompts.size();
                prompts.add(new ParsedPrompt(
                        source.name() + "-" + idx,
                        source.name(),
                        source.sourceUrl(),
                        title,
                        prompt.trim(),
                        GalleryMarkdown.extractMarkdownImages(source, block),
                        sectionTags,
                        category,
                        "",
                        "",
                        block));
            }
        }
        return prompts;
    }
}
