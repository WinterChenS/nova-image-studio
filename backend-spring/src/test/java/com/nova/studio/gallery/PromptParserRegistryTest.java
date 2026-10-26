package com.nova.studio.gallery;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-42 (T15, ADR-40) — 分类推断与 6 种解析器移植单测（Java 移植对拍自前端
 * {@code prompt-gallery-data.ts}，解析器下线前的语义等价基准）。
 */
class PromptParserRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptParserRegistry registry = new PromptParserRegistry(objectMapper);

    private PromptParser parser(String type) {
        return registry.get(type);
    }

    private static final PromptDataSource SOURCE = new PromptDataSource(
            "test-source", "https://example.test/data", "https://github.com/example/repo",
            "nanobanana", "https://example.test/base", null, null);

    // ===== category inference (inferCategory port) =====

    @Test
    void inferCategoryClassifiesPoster() {
        assertThat(GalleryCategory.infer("海报设计", "一张促销海报", List.of())).isEqualTo("海报");
    }

    @Test
    void inferCategoryClassifiesCharacterByEnglish() {
        assertThat(GalleryCategory.infer("", "an oc character portrait", List.of())).isEqualTo("角色");
    }

    @Test
    void inferCategoryClassifiesUiByChinese() {
        assertThat(GalleryCategory.infer("界面设计", "dashboard ui", List.of())).isEqualTo("UI");
    }

    @Test
    void inferCategoryFallsBackToOther() {
        assertThat(GalleryCategory.infer("hello world", "some random text", List.of())).isEqualTo("其他");
    }

    // ===== nanobanana parser =====

    @Test
    void nanobananaParsesSectionsAndPrompts() throws Exception {
        String raw = """
                {"sections":[{"id":"sec1","title":"基础","prompts":[
                  {"id":"p1","title":"海报标题","content":"一张海报","tags":["风格"],"images":["a.png"],"contributor":"x","notes":"n"},
                  {"id":"p2","title":"角色标题","content":"an oc character","tags":[],"images":[]}
                ]}]}
                """;
        List<ParsedPrompt> prompts = parser("nanobanana").parse(SOURCE, raw, url -> "");
        assertThat(prompts).hasSize(2);
        ParsedPrompt first = prompts.get(0);
        assertThat(first.id()).isEqualTo("test-source-sec1-p1-0-0");
        assertThat(first.title()).isEqualTo("海报标题");
        assertThat(first.category()).isEqualTo("海报");
        assertThat(first.images()).containsExactly("a.png");
        assertThat(first.rawSnapshot()).contains("p1");
    }

    // ===== gpt-image-2 parser =====

    @Test
    void gptImage2ParsesRecordsWithCasePrompts() throws Exception {
        PromptDataSource source = new PromptDataSource("gpt-image-2-prompts", "url", "https://github.com/e/r",
                "gpt-image-2", "https://base.test/main", List.of("cases/ui.md"), null);
        String raw = """
                {"records":[{"title":"UI 案例","tweet_url":"https://t.co/abc","image_dir":"cases/ui","category":"UI Cases"}]}
                """;
        String caseMd = """
                ### Case 1: [tweet](https://t.co/abc)
                **Prompt:**
                ```text
                a beautiful dashboard
                ```
                """;
        List<ParsedPrompt> prompts = parser("gpt-image-2").parse(source, raw, url ->
                url.contains("cases/ui.md") ? caseMd : "");
        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0).content()).isEqualTo("a beautiful dashboard");
        assertThat(prompts.get(0).category()).isEqualTo("gpt-image-2");
        assertThat(prompts.get(0).images()).containsExactly("https://base.test/main/cases/ui/output.jpg");
        assertThat(prompts.get(0).tags()).containsExactly("UI");
    }

    // ===== markdown-awesome parser =====

    @Test
    void markdownAwesomeParsesSectionsAndBlocks() throws Exception {
        String raw = """
                # Title
                ## 海报 & 电商
                ### 双十一海报
                ![pic](img1.png)
                **提示词:**
                ```text
                festive poster
                ```
                ## 角色
                ### 角色头像
                **提示词:**
                ```
                portrait of an oc
                ```
                """;
        List<ParsedPrompt> prompts = parser("markdown-awesome").parse(SOURCE, raw, url -> "");
        assertThat(prompts).hasSize(2);
        ParsedPrompt first = prompts.get(0);
        assertThat(first.title()).isEqualTo("双十一海报");
        assertThat(first.content()).isEqualTo("festive poster");
        assertThat(first.tags()).contains("海报", "电商");
        assertThat(first.category()).isEqualTo("海报");
        assertThat(first.images()).containsExactly("https://example.test/base/img1.png");
    }

    // ===== markdown-gpt4o parser =====

    @Test
    void markdownGpt4oParsesInlineCodePrompts() throws Exception {
        String raw = """
                # Awesome GPT4o
                ### 角色海报
                - **提示词文本：**`a cinematic oc poster`
                ### 产品图
                - **提示词文本：**`product shot`
                """;
        List<ParsedPrompt> prompts = parser("markdown-gpt4o").parse(SOURCE, raw, url -> "");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0).title()).isEqualTo("角色海报");
        assertThat(prompts.get(0).content()).isEqualTo("a cinematic oc poster");
        assertThat(prompts.get(0).category()).isEqualTo("gpt4o");
        assertThat(prompts.get(0).tags()).containsExactly("gpt4o");
    }

    // ===== markdown-youmind parser =====

    @Test
    void markdownYouMindParsesNumberedTitles() throws Exception {
        PromptDataSource source = new PromptDataSource("youmind-gpt-image-2", "url", "https://github.com/y/r",
                "markdown-youmind", "https://base.test/main", null, "gpt-image-2");
        String raw = """
                # Awesome
                ### No.1: 电商 - 海报
                #### 提示词
                ```text
                product promo poster
                ```
                ### No.2: 角色 - 头像
                #### 提示词
                ```
                oc avatar
                ```
                """;
        List<ParsedPrompt> prompts = parser("markdown-youmind").parse(source, raw, url -> "");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0).title()).isEqualTo("电商 - 海报");
        assertThat(prompts.get(0).content()).isEqualTo("product promo poster");
        assertThat(prompts.get(0).tags()).contains("gpt-image-2", "电商");
        // 前端 inferCategory 顺序：poster 优先命中「海报」
        assertThat(prompts.get(0).category()).isEqualTo("海报");
    }

    // ===== davidwu-json parser =====

    @Test
    void davidWuParsesArrayItems() throws Exception {
        String raw = """
                [{"title_cn":"海报示例","title_en":"poster","prompt":"a poster","image":"img/1.png",
                  "category_cn":"海报","author":"作者甲","source":"github","needs_ref":true,"note":"备注"},
                 {"title_cn":"","title_en":"empty prompt","prompt":"  ","image":""}]
                """;
        List<ParsedPrompt> prompts = parser("davidwu-json").parse(SOURCE, raw, url -> "");
        assertThat(prompts).hasSize(1);
        ParsedPrompt item = prompts.get(0);
        assertThat(item.title()).isEqualTo("海报示例");
        assertThat(item.content()).isEqualTo("a poster");
        assertThat(item.images()).containsExactly("https://example.test/base/img/1.png");
        assertThat(item.tags()).contains("海报", "作者甲", "需要参考图");
        assertThat(item.category()).isEqualTo("海报");
        assertThat(item.contributor()).isEqualTo("作者甲");
        assertThat(item.notes()).isEqualTo("备注");
    }
}
