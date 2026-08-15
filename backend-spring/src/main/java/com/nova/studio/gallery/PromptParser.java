package com.nova.studio.gallery;

import java.util.List;

/**
 * WIN-42 (T15, ADR-40) — 提示广场源解析器接口（Java 移植自前端
 * {@code prompt-gallery-data.ts} 6 种格式）。实现按 {@code type} 注册到
 * {@link PromptParserRegistry}。
 */
public interface PromptParser {

    /** 解析器类型标识（对应 PromptDataSource.type）。 */
    String type();

    /**
     * 解析源内容为入库条目。
     *
     * @param source  数据源配置
     * @param raw     远程源原始内容（URL fetch 结果）
     * @param fetcher 额外资源拉取器（gpt-image-2 的 case 文件）
     * @return 解析出的提示词条目（可为空）
     * @throws Exception 解析失败时抛出（由同步服务单源容错捕获）
     */
    List<ParsedPrompt> parse(PromptDataSource source, String raw, PromptSourceFetcher fetcher) throws Exception;
}
