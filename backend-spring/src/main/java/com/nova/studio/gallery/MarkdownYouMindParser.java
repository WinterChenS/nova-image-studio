package com.nova.studio.gallery;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * WIN-42 (T15, ADR-40) — markdown-youmind 解析器（Java 移植自前端
 * {@code parseMarkdownYouMind}）：标题格式 {@code ### No.N: title}，提示词
 * {@code #### ...提示词 ... ```...```} 代码块；标签含 modelTag。
 */
public class MarkdownYouMindParser implements PromptParser {

    private static final Pattern TITLE_RE = Pattern.compile("^###\\s+No\\.\\s*\\d+:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern PROMPT_RE = Pattern.compile(
            "#### .*?提示词\\s*\\r?\\n\\s*```[\\w-]*\\r?\\n([\\s\\S]*?)\\r?\\n```");

    @Override
    public String type() {
        return "markdown-youmind";
    }

    @Override
    public List<ParsedPrompt> parse(PromptDataSource source, String raw, PromptSourceFetcher fetcher) {
        List<ParsedPrompt> prompts = new ArrayList<>();
        String modelTag = source.modelTag() == null ? "" : source.modelTag();
        for (String block : GalleryMarkdown.splitBeforeHeading(raw, "### ")) {
            String title = GalleryMarkdown.firstMatch(block, TITLE_RE).trim();
            String prompt = GalleryMarkdown.firstMatch(block, PROMPT_RE);
            if (title.isEmpty() || prompt.isEmpty()) {
                continue;
            }
            List<String> tags = GalleryMarkdown.youMindTags(title, modelTag);
            String category = GalleryCategory.infer(title, prompt, tags);
            int idx = prompts.size();
            prompts.add(new ParsedPrompt(
                    source.name() + "-" + idx,
                    source.name(),
                    source.sourceUrl(),
                    title,
                    prompt.trim(),
                    GalleryMarkdown.extractMarkdownImages(source, block),
                    tags,
                    category,
                    "",
                    "",
                    block));
        }
        return prompts;
    }
}
