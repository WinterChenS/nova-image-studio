package com.nova.studio.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WIN-39 (WIN-41 T10, ADR-44/A9) — Agent 自动上下文压缩：长会话（消息数超过
 * {@code agent.contextCompressThreshold}，默认 60）将最旧部分折叠进摘要，最近
 * {@code agent.contextKeepRecent}（默认 20）条保留原文进 LLM 上下文；增量折叠保持
 * 成本线性；摘要生成失败 → 本轮不压缩继续对话（绝不阻塞）；全文仍持久化可查看
 * （压缩仅影响送入 LLM 的上下文）。
 *
 * <p>摘要结构：{text, model, foldedBeforeMessageId, foldedCount, foldedAt}。
 */
@Component
public class ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);

    public static final int DEFAULT_COMPRESS_THRESHOLD = 60;
    public static final int DEFAULT_KEEP_RECENT = 20;

    /** 摘要生成器（真实实现 = 文本 LLM 非流式调用，复用账号池；单测可 mock）。 */
    public interface SummaryGenerator {
        /**
         * 对「旧摘要 + 本次折叠块」增量生成新摘要。
         *
         * @param previousSummary 旧摘要文本（可能为空）
         * @param foldedTurns     本次折叠的历史消息（角色 + 文本，已过滤 system-note/divider）
         * @return 新摘要文本
         * @throws Exception 上游不可用/超时（调用方降级）
         */
        String generateSummary(String previousSummary, List<AgentRequestBodyBuilder.HistoryTurn> foldedTurns)
                throws Exception;
    }

    /** 压缩执行结果。 */
    public record CompressResult(boolean compressed, String summaryText, String foldedBeforeMessageId,
                                 int foldedCount, Instant foldedAt) {

        /** context_summary JSON 形状（ADR-44）。 */
        public Map<String, Object> toSummaryMap(String model) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("text", summaryText);
            if (model != null) {
                map.put("model", model);
            }
            map.put("foldedBeforeMessageId", foldedBeforeMessageId);
            map.put("foldedCount", foldedCount);
            map.put("foldedAt", foldedAt.toString());
            return map;
        }
    }

    /**
     * 判断是否触发压缩：有效消息数（user/assistant，排除 system-note/divider）超过阈值。
     */
    public boolean shouldCompress(List<AgentRequestBodyBuilder.HistoryTurn> turns, int threshold) {
        if (turns == null || threshold <= 0) {
            return false;
        }
        int real = 0;
        for (AgentRequestBodyBuilder.HistoryTurn turn : turns) {
            if (turn == null || turn.role() == null || turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            if ("system-note".equals(turn.role()) || "context-divider".equals(turn.role())) {
                continue;
            }
            real++;
        }
        return real > threshold;
    }

    /**
     * 执行压缩：折叠最旧 (real - keepRecent) 条 → 增量摘要 → 返回结果。
     * 摘要生成失败 → 返回 compressed=false（本轮不压缩，下轮重试，绝不阻塞）。
     *
     * @param turns           完整历史（含 system-note/divider，会被跳过）
     * @param keepRecent      保留原文的最近消息数
     * @param previousSummary 旧摘要（增量折叠）
     * @param generator       摘要生成器
     * @param model           摘要模型标记（写入 context_summary.model）
     */
    public CompressResult compress(List<AgentRequestBodyBuilder.HistoryTurn> turns, int keepRecent,
                                   String previousSummary, SummaryGenerator generator, String model) {
        if (turns == null || turns.isEmpty() || keepRecent < 0 || generator == null) {
            return new CompressResult(false, null, null, 0, null);
        }
        // 过滤出可折叠的消息（真实对话消息）
        List<AgentRequestBodyBuilder.HistoryTurn> real = new ArrayList<>();
        for (AgentRequestBodyBuilder.HistoryTurn turn : turns) {
            if (turn == null || turn.role() == null || turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            if ("system-note".equals(turn.role()) || "context-divider".equals(turn.role())) {
                continue;
            }
            real.add(turn);
        }
        int foldCount = real.size() - keepRecent;
        if (foldCount <= 0) {
            return new CompressResult(false, null, null, 0, null);
        }
        List<AgentRequestBodyBuilder.HistoryTurn> folded = real.subList(0, foldCount);
        List<AgentRequestBodyBuilder.HistoryTurn> kept = real.subList(foldCount, real.size());
        String foldedBeforeId = kept.isEmpty() ? null : kept.get(0).id();   // 最早未压缩消息 id（跳转锚点）
        String summaryText;
        try {
            summaryText = generator.generateSummary(
                    previousSummary == null ? "" : previousSummary, folded);
        } catch (Exception e) {
            log.warn("[agent-compress] 摘要生成失败，本轮不压缩（对话继续）: {}", e.getMessage());
            return new CompressResult(false, null, null, 0, null);
        }
        if (summaryText == null || summaryText.isBlank()) {
            return new CompressResult(false, null, null, 0, null);
        }
        return new CompressResult(true, summaryText.trim(), foldedBeforeId, foldCount, Instant.now());
    }

    /** 计算本轮应折叠的消息数（供调用方决定是否压缩）。 */
    public int foldCount(int realMessageCount, int keepRecent) {
        return Math.max(0, realMessageCount - keepRecent);
    }
}
