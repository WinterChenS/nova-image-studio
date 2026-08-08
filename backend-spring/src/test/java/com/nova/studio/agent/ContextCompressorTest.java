package com.nova.studio.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-41 (T10, ADR-44/A9) — 自动上下文压缩：触发阈值、增量折叠（旧摘要 + 折叠块）、
 * 保留最近 N 条、摘要失败降级不阻塞、context_summary 形状。
 */
class ContextCompressorTest {

    private final ContextCompressor compressor = new ContextCompressor();

    private List<AgentRequestBodyBuilder.HistoryTurn> turns(int count) {
        List<AgentRequestBodyBuilder.HistoryTurn> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(new AgentRequestBodyBuilder.HistoryTurn("msg-" + i, i % 2 == 0 ? "user" : "assistant", "消息" + i));
        }
        return list;
    }

    @Test
    void shouldCompressWhenRealCountExceedsThreshold() {
        assertThat(compressor.shouldCompress(turns(61), 60)).isTrue();
        assertThat(compressor.shouldCompress(turns(60), 60)).isFalse();
        assertThat(compressor.shouldCompress(turns(20), 60)).isFalse();
    }

    @Test
    void systemNoteAndDividerDoNotCountTowardsThreshold() {
        List<AgentRequestBodyBuilder.HistoryTurn> mixed = new ArrayList<>(turns(59));
        mixed.add(new AgentRequestBodyBuilder.HistoryTurn("n1", "system-note", "已取消"));
        mixed.add(new AgentRequestBodyBuilder.HistoryTurn("d1", "context-divider", "分隔"));
        // 59 条真实 + 2 条非真实 → 不触发
        assertThat(compressor.shouldCompress(mixed, 60)).isFalse();
    }

    @Test
    void compressFoldsOldestAndKeepsRecentWithIncrementalSummary() {
        List<AgentRequestBodyBuilder.HistoryTurn> all = turns(70);
        ContextCompressor.CompressResult result = compressor.compress(all, 20, "旧摘要内容",
                (previous, folded) -> previous + "|折叠" + folded.size() + "条", "model-x");
        assertThat(result.compressed()).isTrue();
        assertThat(result.foldedCount()).isEqualTo(50);
        assertThat(result.foldedBeforeMessageId()).isEqualTo("msg-51");   // 最早未压缩消息
        assertThat(result.summaryText()).isEqualTo("旧摘要内容|折叠50条");
        assertThat(result.toSummaryMap("model-x"))
                .containsEntry("model", "model-x")
                .containsEntry("foldedCount", 50)
                .containsEntry("foldedBeforeMessageId", "msg-51");
    }

    @Test
    void compressWhenBelowKeepRecentDoesNothing() {
        ContextCompressor.CompressResult result = compressor.compress(turns(10), 20, null,
                (previous, folded) -> "x", "m");
        assertThat(result.compressed()).isFalse();
    }

    @Test
    void summaryFailureDegradesWithoutBlocking() {
        ContextCompressor.CompressResult result = compressor.compress(turns(70), 20, null,
                (previous, folded) -> { throw new RuntimeException("上游不可用"); }, "m");
        assertThat(result.compressed()).isFalse();
        assertThat(result.foldedCount()).isZero();
    }

    @Test
    void blankSummaryIsTreatedAsDegrade() {
        ContextCompressor.CompressResult result = compressor.compress(turns(70), 20, null,
                (previous, folded) -> "  ", "m");
        assertThat(result.compressed()).isFalse();
    }

    @Test
    void foldCountHelper() {
        assertThat(compressor.foldCount(70, 20)).isEqualTo(50);
        assertThat(compressor.foldCount(10, 20)).isZero();
    }
}
