package com.nova.studio.gallery;

/**
 * WIN-42 (T15, ADR-40) — 远程源内容拉取器（后端 fetch，ADR-40 依赖：经
 * {@code proxy.ccode.vip} 前缀或直连 raw.githubusercontent）。生产实现为
 * {@link HttpPromptSourceFetcher}；测试可注入桩实现。
 */
public interface PromptSourceFetcher {

    /**
     * 拉取 URL 文本内容。
     *
     * @throws Exception 拉取失败时抛出（由同步服务单源容错捕获，不影响其他源）
     */
    String fetch(String url) throws Exception;
}
