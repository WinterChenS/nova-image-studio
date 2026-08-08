package com.nova.studio.agent;

import com.nova.studio.accountpool.AccountHealthService;
import com.nova.studio.accountpool.AccountScheduler;
import com.nova.studio.accountpool.AccountService;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.audit.UsageCollector;
import com.nova.studio.conversation.ConversationMessageRepository;
import com.nova.studio.conversation.ConversationRepository;
import com.nova.studio.conversation.ConversationService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import com.nova.studio.textproxy.TextProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WIN-39 (WIN-41 T9/T10) — Agent 会话托管服务：后端组装上下文（历史/图片目录/
 * system 指令/工具 schema/自动压缩摘要）→ 经 {@link TextProxyService} 转发上游
 * （复用账号池选号/健康/重试边界）→ 统一事件转译输出（ARCH Part G.3）→ 消息落库 +
 * usage（ref_type='agent'，AC-7）。会话级单飞锁（同会话并发 → 409 CONCURRENT_STREAM）。
 *
 * <p>重试/超时语义镜像前端 {@code agent-chat-client.ts}：单次尝试 45s 超时、最多 3 次
 * 尝试、可重试错误分类（R3）。前端不再回传完整历史/协议（会话托管，FR-2.1）。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    public static final int MAX_ATTEMPTS = 3;
    public static final long DEFAULT_ATTEMPT_TIMEOUT_MS = 45_000;
    public static final long DEFAULT_DESCRIBE_TIMEOUT_MS = 20_000;

    public static final String STATUS_DELETED = "deleted";

    private final ConversationService conversationService;
    private final ConversationMessageRepository messageRepository;
    private final AssetService assetService;
    private final SettingsService settingsService;
    private final AgentRequestBodyBuilder bodyBuilder;
    private final AgentStreamTranslator translator;
    private final ContextCompressor compressor;
    private final TextProxyService textProxyService;
    private final CatalogModelService catalogModelService;
    private final AccountScheduler accountScheduler;
    private final AccountHealthService accountHealthService;
    private final AccountService accountService;
    private final UsageCollector usageCollector;
    private final ObjectMapper objectMapper;
    private final long attemptTimeoutMs;

    /** 会话级单飞锁（JVM 内存态，H13；多实例化时迁 Redis，演进路线 Part I.2）。 */
    private final Set<String> activeStreams = ConcurrentHashMap.newKeySet();

    public AgentChatService(ConversationService conversationService,
                            ConversationMessageRepository messageRepository,
                            AssetService assetService,
                            SettingsService settingsService,
                            AgentRequestBodyBuilder bodyBuilder,
                            AgentStreamTranslator translator,
                            ContextCompressor compressor,
                            TextProxyService textProxyService,
                            CatalogModelService catalogModelService,
                            AccountScheduler accountScheduler,
                            AccountHealthService accountHealthService,
                            AccountService accountService,
                            UsageCollector usageCollector,
                            ObjectMapper objectMapper,
                            @Value("${nova.agent.attempt-timeout-ms:45000}") long attemptTimeoutMs) {
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.assetService = assetService;
        this.settingsService = settingsService;
        this.bodyBuilder = bodyBuilder;
        this.translator = translator;
        this.compressor = compressor;
        this.textProxyService = textProxyService;
        this.catalogModelService = catalogModelService;
        this.accountScheduler = accountScheduler;
        this.accountHealthService = accountHealthService;
        this.accountService = accountService;
        this.usageCollector = usageCollector;
        this.objectMapper = objectMapper;
        this.attemptTimeoutMs = attemptTimeoutMs > 0 ? attemptTimeoutMs : DEFAULT_ATTEMPT_TIMEOUT_MS;
    }

    // ===== 会话单飞锁 =====

    public boolean tryAcquireStream(String conversationId) {
        return activeStreams.add(conversationId);
    }

    public void releaseStream(String conversationId) {
        activeStreams.remove(conversationId);
    }

    public boolean isStreamActive(String conversationId) {
        return activeStreams.contains(conversationId);
    }

    // ===== SSE 统一事件写出 =====

    /** 统一事件写出器（Servlet 实现写 SSE 帧；测试实现收集）。 */
    public interface SseWriter {
        void event(String type, Map<String, Object> data) throws IOException;
    }

    // ===== 会话聊天（SSE 统一事件流）=====

    /**
     * 发送消息 → SSE 统一事件流。请求体：{text?, imageAssetIds?, webSearch?, model
     * (目录文本模型 UUID), reeditFromMessageId?, clientMessageId?}（G.1：前端不传协议/
     * 完整历史）。返回 {@code done} 事件 + 落库；同会话并发 → 409。
     */
    public ChatResult streamChat(UUID userId, String conversationId, JsonNode body, SseWriter writer)
            throws IOException {
        ConversationRepository.ConversationRow conversation = conversationService.getOwned(userId, conversationId);
        if (STATUS_DELETED.equals(conversation.status())) {
            throw new HttpErrorException(409, "CONVERSATION_DELETED", "会话已删除，请从回收站恢复");
        }
        if (!tryAcquireStream(conversationId)) {
            throw new HttpErrorException(409, "CONCURRENT_STREAM", "该会话正在生成中，请稍后再试");
        }
        try {
            return doStreamChat(userId, conversationId, conversation, body, writer);
        } finally {
            releaseStream(conversationId);
        }
    }

    /** 会话聊天执行结果（消息落库 id，供控制器/测试断言）。 */
    public record ChatResult(String assistantMessageId, String userMessageId) {
    }

    private ChatResult doStreamChat(UUID userId, String conversationId,
                                    ConversationRepository.ConversationRow conversation,
                                    JsonNode body, SseWriter writer) throws IOException {
        String modelField = body == null ? null : text(body, "model");
        if (modelField == null || modelField.isBlank()) {
            throw new IllegalArgumentException("缺少文本模型 model");
        }
        CatalogModelRepository.Row model = resolveTextModel(modelField);
        String userText = body.hasNonNull("text") ? body.get("text").asText() : "";
        List<String> imageAssetIds = textArray(body, "imageAssetIds");
        if (userText.isBlank() && imageAssetIds.isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        boolean webSearch = body.hasNonNull("webSearch") && body.get("webSearch").asBoolean();
        String clientMessageId = body.hasNonNull("clientMessageId") ? body.get("clientMessageId").asText() : null;
        long startedAt = System.currentTimeMillis();

        // 1) 用户消息落库（clientMessageId 作为主键，撤回/回滚 id 一致）
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("text", userText);
        if (!imageAssetIds.isEmpty()) {
            var arr = objectMapper.createArrayNode();
            imageAssetIds.forEach(arr::add);
            userMsg.set("imageIds", arr);
        }
        ConversationMessageRepository.MessageRow userRow =
                conversationService.appendMessage(userId, conversationId, userMsg, clientMessageId);
        String userMessageId = userRow.id();
        log.info("[agent-chat] 会话消息发送: user={}, conversation={}, message={}, model={}",
                userId, conversationId, userMessageId, model.modelId());

        // 2) 上下文组装（历史 + 图片目录 + 指令 + 自动压缩摘要，ADR-44）
        List<AgentRequestBodyBuilder.HistoryTurn> turns = loadHistoryTurns(userId, conversationId);
        List<AgentRequestBodyBuilder.CatalogEntry> catalog = loadCatalog(userId, conversationId);
        List<AgentRequestBodyBuilder.ModelEntry> modelCatalog = loadModelCatalog();
        String instructions = bodyBuilder.buildInstructions(catalog, modelCatalog);
        String contextSummary = tryCompress(userId, conversationId, conversation, turns, model.id().toString());

        // 3) 上游转发 + 统一转译（重试 ≤3 / 单次尝试 45s）
        AgentStreamTranslator.StreamState state = new AgentStreamTranslator.StreamState();
        StringBuilder reasoningBuf = new StringBuilder();
        boolean done = false;
        boolean failed = false;
        String errorMessage = null;
        String errorCode = "UPSTREAM_ERROR";
        boolean retried = false;
        int attempts = 0;
        Set<UUID> tried = new HashSet<>();
        UUID lastAccountId = null;
        String lastProtocol = null;

        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            AccountScheduler.SelectedAccount selected;
            try {
                selected = accountScheduler.select(model, tried);
            } catch (HttpErrorException e) {
                errorMessage = e.getMessage();
                errorCode = e.getCode() == null ? "NO_ACCOUNT" : e.getCode();
                failed = true;
                break;
            }
            tried.add(selected.accountId());
            lastAccountId = selected.accountId();
            lastProtocol = selected.protocol();
            try {
                TextProxyService.Target target = textProxyService.buildTarget(
                        selected.protocol(), selected.baseUrl(), selected.apiKey(), model.modelId(), true);
                JsonNode requestBody = bodyBuilder.buildRequestBody(
                        selected.protocol(), model.modelId(), turns, instructions,
                        webSearch && supportsNativeWebSearch(selected.protocol()), contextSummary);
                TextProxyService.ProxyExchange exchange = textProxyService.exchange(
                        target, requestBody, Duration.ofMillis(attemptTimeoutMs));
                if (exchange.streamed()) {
                    readAndTranslate(selected.protocol(), exchange.stream(), state, reasoningBuf,
                            (type, data) -> {
                                if (AgentStreamTranslator.EV_REASONING.equals(type)
                                        && data != null && data.get("text") != null) {
                                    reasoningBuf.append(data.get("text"));
                                }
                                writer.event(type, data);
                            });
                    recordSuccess(selected);
                    done = true;
                    break;
                }
                // 上游未按流式返回（非 2xx 或异常响应）
                AccountHealthService.ErrorKind kind = classifyStatus(exchange.status());
                recordFailure(selected, kind, "上游返回 " + exchange.status());
                if (kind.retriable() && attempts < MAX_ATTEMPTS) {
                    retried = true;
                    writer.event(AgentStreamTranslator.EV_RETRY, retryData(attempts + 1, exchange.status()));
                    state = new AgentStreamTranslator.StreamState();
                    reasoningBuf.setLength(0);
                    continue;
                }
                failed = true;
                errorCode = kind.name();
                errorMessage = "上游返回 " + exchange.status() + (exchange.jsonBody() == null ? "" : "：" + truncate(exchange.jsonBody(), 200));
                break;
            } catch (IOException e) {
                // 客户端断开（写 SSE 失败）→ 已产出片段落库后终止
                persistPartial(userId, conversationId, state, reasoningBuf, lastProtocol);
                throw e;
            } catch (Exception e) {
                AccountHealthService.ErrorKind kind = accountHealthService.classify(e);
                recordFailure(selected, kind, e.getMessage());
                if (kind.retriable() && attempts < MAX_ATTEMPTS) {
                    retried = true;
                    writer.event(AgentStreamTranslator.EV_RETRY, retryData(attempts + 1, e.getMessage()));
                    state = new AgentStreamTranslator.StreamState();
                    reasoningBuf.setLength(0);
                    continue;
                }
                failed = true;
                errorCode = kind.name();
                errorMessage = normalizeError(e);
                break;
            } finally {
                accountScheduler.release(selected.accountId());
            }
        }

        // 4) 提案解析（未落 assistant 消息，前端按 pending 恢复）或文本落库
        Map<String, Object> proposal = state.toolArgs == null ? null : translator.parseProposal(state.toolArgs);
        String assistantMessageId = null;
        String reasoning = reasoningBuf.toString().trim();
        if (failed) {
            persistPartial(userId, conversationId, state, reasoningBuf, lastProtocol);
        } else if (proposal == null && state.accumulated.length() > 0) {
            assistantMessageId = persistAssistant(userId, conversationId, state.accumulated.toString(),
                    reasoning, null, null);
        } else if (proposal == null) {
            // 空响应视为失败（无文本无提案）
            failed = true;
            errorCode = "EMPTY_RESPONSE";
            errorMessage = "模型未返回有效内容";
        }

        // 5) usage（ref_type='agent'，ref_id=userMessageId，AC-7）
        boolean finalFailed = failed;
        usageCollector.recordAgentUsage(new UsageCollector.AgentUsage(
                userMessageId, userId, model.id(), lastAccountId, lastProtocol,
                retried, finalFailed, null, null, System.currentTimeMillis() - startedAt));

        // 6) 统一事件收尾
        if (failed) {
            writer.event(AgentStreamTranslator.EV_ERROR, errorData(errorCode, errorMessage));
            return new ChatResult(null, userMessageId);
        }
        if (proposal != null) {
            writer.event(AgentStreamTranslator.EV_PROPOSAL, proposal);
            writer.event(AgentStreamTranslator.EV_DONE, Map.of("messageId", "", "taskId", "", "proposalId", ""));
            return new ChatResult(null, userMessageId);
        }
        writer.event(AgentStreamTranslator.EV_DONE, Map.of(
                "messageId", assistantMessageId == null ? "" : assistantMessageId,
                "taskId", "", "proposalId", ""));
        return new ChatResult(assistantMessageId, userMessageId);
    }

    // ===== 图片描述（vision，替代前端 describing 阶段直连）=====

    /**
     * 图片描述：读 assets 字节 → 构造视觉请求 → TextProxyService 非流式转发 →
     * 按协议提取文本输出。重试 ≤3 / 单次尝试 20s。
     */
    public String describeImage(UUID userId, String assetId, String modelField) throws IOException {
        AssetService.StoredAssetFile stored = assetService.getFile(userId, assetId)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "图片不存在"));
        if (modelField == null || modelField.isBlank()) {
            throw new IllegalArgumentException("缺少文本模型 model");
        }
        CatalogModelRepository.Row model = resolveTextModel(modelField);
        String dataUrl = "data:" + (stored.contentType() == null ? "image/png" : stored.contentType())
                + ";base64," + java.util.Base64.getEncoder().encodeToString(stored.data());

        long startedAt = System.currentTimeMillis();
        int attempts = 0;
        Set<UUID> tried = new HashSet<>();
        UUID lastAccountId = null;
        String lastProtocol = null;
        boolean retried = false;
        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            AccountScheduler.SelectedAccount selected;
            try {
                selected = accountScheduler.select(model, tried);
            } catch (HttpErrorException e) {
                throw new HttpErrorException(502, "NO_ACCOUNT", "没有可用文本模型账号：" + e.getMessage());
            }
            tried.add(selected.accountId());
            lastAccountId = selected.accountId();
            lastProtocol = selected.protocol();
            try {
                TextProxyService.Target target = textProxyService.buildTarget(
                        selected.protocol(), selected.baseUrl(), selected.apiKey(), model.modelId(), false);
                JsonNode requestBody = bodyBuilder.buildDescribeRequestBody(
                        selected.protocol(), model.modelId(), dataUrl);
                TextProxyService.ProxyExchange exchange = textProxyService.exchange(
                        target, requestBody, Duration.ofMillis(DEFAULT_DESCRIBE_TIMEOUT_MS));
                if (exchange.status() >= 200 && exchange.status() < 300) {
                    recordSuccess(selected);
                    String output = bodyBuilder.extractTextOutput(selected.protocol(),
                            parseJson(exchange.jsonBody()));
                    usageCollector.recordAgentUsage(new UsageCollector.AgentUsage(
                            "describe-" + assetId, userId, model.id(), lastAccountId, lastProtocol,
                            retried, false, null, null, System.currentTimeMillis() - startedAt));
                    return output;
                }
                AccountHealthService.ErrorKind kind = classifyStatus(exchange.status());
                recordFailure(selected, kind, "上游返回 " + exchange.status());
                if (kind.retriable() && attempts < MAX_ATTEMPTS) {
                    retried = true;
                    continue;
                }
                usageCollector.recordAgentUsage(new UsageCollector.AgentUsage(
                        "describe-" + assetId, userId, model.id(), lastAccountId, lastProtocol,
                        retried, true, null, null, System.currentTimeMillis() - startedAt));
                throw new HttpErrorException(502, kind.name(), "上游返回 " + exchange.status());
            } catch (Exception e) {
                AccountHealthService.ErrorKind kind = accountHealthService.classify(e);
                recordFailure(selected, kind, e.getMessage());
                if (kind.retriable() && attempts < MAX_ATTEMPTS) {
                    retried = true;
                    continue;
                }
                usageCollector.recordAgentUsage(new UsageCollector.AgentUsage(
                        "describe-" + assetId, userId, model.id(), lastAccountId, lastProtocol,
                        retried, true, null, null, System.currentTimeMillis() - startedAt));
                throw new HttpErrorException(502, kind.name(), "图片描述失败：" + normalizeError(e));
            } finally {
                accountScheduler.release(selected.accountId());
            }
        }
        throw new HttpErrorException(502, "UPSTREAM_ERROR", "图片描述失败");
    }

    // ===== 上下文组装 =====

    private List<AgentRequestBodyBuilder.HistoryTurn> loadHistoryTurns(UUID userId, String conversationId) {
        List<AgentRequestBodyBuilder.HistoryTurn> turns = new ArrayList<>();
        ConversationMessageRepository.MessagePage page =
                messageRepository.listByConversation(conversationId, userId.toString(), null, 500);
        List<ConversationMessageRepository.MessageRow> rows = new ArrayList<>(page.items());
        java.util.Collections.reverse(rows);   // created_at DESC → ASC
        for (ConversationMessageRepository.MessageRow row : rows) {
            turns.add(new AgentRequestBodyBuilder.HistoryTurn(row.id(), row.role(), row.text()));
        }
        return turns;
    }

    private List<AgentRequestBodyBuilder.CatalogEntry> loadCatalog(UUID userId, String conversationId) {
        List<AgentRequestBodyBuilder.CatalogEntry> catalog = new ArrayList<>();
        for (AssetRepository.AssetRow image : assetService.listConversationImages(userId, conversationId)) {
            catalog.add(new AgentRequestBodyBuilder.CatalogEntry(image.id(), parseExtraDescription(image.extra())));
        }
        return catalog;
    }

    /** 可用图像模型目录（含 available 标记，镜像前端 getCompleteImageModels 过滤）。 */
    private List<AgentRequestBodyBuilder.ModelEntry> loadModelCatalog() {
        List<AgentRequestBodyBuilder.ModelEntry> entries = new ArrayList<>();
        for (Map<String, Object> dto : catalogModelService.listPublicCatalog()) {
            if (!"image".equals(dto.get("type"))) {
                continue;
            }
            if (!Boolean.TRUE.equals(dto.get("available"))) {
                continue;
            }
            entries.add(new AgentRequestBodyBuilder.ModelEntry(
                    String.valueOf(dto.get("id")),
                    String.valueOf(dto.get("name")),
                    dto.get("maxOutputSize") == null ? "1K" : String.valueOf(dto.get("maxOutputSize"))));
        }
        return entries;
    }

    /** ADR-44 自动上下文压缩：触发/增量折叠/失败降级（绝不阻塞）。 */
    private String tryCompress(UUID userId, String conversationId,
                               ConversationRepository.ConversationRow conversation,
                               List<AgentRequestBodyBuilder.HistoryTurn> turns, String modelId) {
        try {
            int threshold = settingsService.getInt(userId,
                    SettingsService.KEY_AGENT_CONTEXT_COMPRESS_THRESHOLD,
                    ContextCompressor.DEFAULT_COMPRESS_THRESHOLD);
            int keepRecent = settingsService.getInt(userId,
                    SettingsService.KEY_AGENT_CONTEXT_KEEP_RECENT,
                    ContextCompressor.DEFAULT_KEEP_RECENT);
            if (!compressor.shouldCompress(turns, threshold)) {
                return null;
            }
            String previous = parseSummaryText(conversation.contextSummary());
            ContextCompressor.CompressResult result = compressor.compress(turns, keepRecent, previous,
                    (oldSummary, folded) -> summarizeBlock(oldSummary, folded, modelId), modelId);
            if (result.compressed()) {
                ObjectNode summary = objectMapper.valueToTree(result.toSummaryMap(modelId));
                conversationService.patch(userId, conversationId,
                        objectMapper.createObjectNode().set("contextSummary", summary));
                log.info("[agent-compress] 会话自动压缩: user={}, conversation={}, folded={}",
                        userId, conversationId, result.foldedCount());
                return result.summaryText();
            }
        } catch (Exception e) {
            log.warn("[agent-compress] 压缩降级（本轮不压缩，对话继续）: {}", e.getMessage());
        }
        return null;
    }

    /** 摘要生成（增量折叠）：文本 LLM 非流式调用，失败抛异常 → 调用方降级。 */
    private String summarizeBlock(String previousSummary,
                                  List<AgentRequestBodyBuilder.HistoryTurn> foldedTurns,
                                  String catalogModelId) throws Exception {
        CatalogModelRepository.Row model = resolveTextModel(catalogModelId);
        StringBuilder block = new StringBuilder();
        for (AgentRequestBodyBuilder.HistoryTurn turn : foldedTurns) {
            block.append("[").append("user".equals(turn.role()) ? "用户" : "助手").append("] ")
                    .append(turn.text()).append('\n');
        }
        String prompt = "你是对话摘要助手。请把下面给定的历史对话压缩为一段简体中文摘要，保留关键主题、已完成的生图请求与参数、用户偏好。"
                + "只输出摘要本身。\n\n"
                + (previousSummary == null || previousSummary.isBlank() ? "" : "已有摘要（保持其中的信息，并融合新内容）：\n" + previousSummary + "\n\n")
                + "新增对话：\n" + block;
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model.modelId());
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt);

        int attempts = 0;
        Set<UUID> tried = new HashSet<>();
        RuntimeException lastError = null;
        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            AccountScheduler.SelectedAccount selected;
            try {
                selected = accountScheduler.select(model, tried);
            } catch (HttpErrorException e) {
                throw new RuntimeException("没有可用文本模型账号", e);
            }
            tried.add(selected.accountId());
            try {
                TextProxyService.Target target = textProxyService.buildTarget(
                        selected.protocol(), selected.baseUrl(), selected.apiKey(), model.modelId(), false);
                TextProxyService.ProxyExchange exchange = textProxyService.exchange(
                        target, body, Duration.ofMillis(DEFAULT_DESCRIBE_TIMEOUT_MS));
                if (exchange.status() >= 200 && exchange.status() < 300) {
                    recordSuccess(selected);
                    String output = bodyBuilder.extractTextOutput(selected.protocol(),
                            parseJson(exchange.jsonBody()));
                    if (output != null && !output.isBlank()) {
                        return output.trim();
                    }
                    throw new RuntimeException("摘要模型未返回内容");
                }
                AccountHealthService.ErrorKind kind = classifyStatus(exchange.status());
                recordFailure(selected, kind, "上游返回 " + exchange.status());
                if (kind.retriable() && attempts < MAX_ATTEMPTS) {
                    continue;
                }
                throw new RuntimeException("上游返回 " + exchange.status());
            } catch (RuntimeException e) {
                lastError = e;
                if (attempts >= MAX_ATTEMPTS) {
                    throw e;
                }
            } finally {
                accountScheduler.release(selected.accountId());
            }
        }
        throw lastError == null ? new RuntimeException("摘要生成失败") : lastError;
    }

    // ===== SSE 读取 + 转译 =====

    private void readAndTranslate(String protocol, InputStream stream,
                                  AgentStreamTranslator.StreamState state, StringBuilder reasoningBuf,
                                  AgentStreamTranslator.Sink sink) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            String rawEvent = null;
            StringBuilder dataBuf = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    rawEvent = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (dataBuf.length() > 0) {
                        dataBuf.append('\n');
                    }
                    dataBuf.append(line.substring(5).trim());
                } else if (line.isEmpty()) {
                    if (dataBuf.length() > 0) {
                        translator.handle(protocol, rawEvent, dataBuf.toString(), state, sink);
                        if (state.accumulated.length() > 0) {
                            // reasoning 已由 translator 直接 sink；此处仅用于落库累积
                        }
                        dataBuf.setLength(0);
                        rawEvent = null;
                    }
                } else if (line.startsWith(":") || line.isBlank()) {
                    // 注释/空行忽略
                }
            }
            if (dataBuf.length() > 0) {
                translator.handle(protocol, rawEvent, dataBuf.toString(), state, sink);
            }
        }
    }

    // ===== 落库 =====

    private String persistAssistant(UUID userId, String conversationId, String text,
                                    String reasoning, String taskId, Boolean webSearchUsed) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("role", "assistant");
        msg.put("text", text);
        if (reasoning != null && !reasoning.isBlank()) {
            msg.put("reasoning", reasoning);
        }
        if (taskId != null) {
            msg.put("taskId", taskId);
        }
        if (webSearchUsed != null) {
            msg.put("webSearchUsed", webSearchUsed);
        }
        return conversationService.appendMessage(userId, conversationId, msg, null).id();
    }

    private void persistPartial(UUID userId, String conversationId,
                                AgentStreamTranslator.StreamState state, StringBuilder reasoningBuf,
                                String protocol) {
        try {
            if (state.accumulated.length() > 0 || reasoningBuf.length() > 0) {
                persistAssistant(userId, conversationId, state.accumulated.toString(),
                        reasoningBuf.toString().trim(), null, null);
                log.info("[agent-chat] 流中断已产出片段落库: conversation={}, chars={}",
                        conversationId, state.accumulated.length());
            }
        } catch (Exception e) {
            log.warn("[agent-chat] 片段落库失败（尽力而为）: {}", e.getMessage());
        }
    }

    // ===== helpers =====

    // ===== 供控制器 raw 透传回退模式使用的公开方法 =====

    /** 解析目录文本模型（raw 透传回退用）。 */
    public CatalogModelRepository.Row resolveTextModelPublic(String modelField) {
        try {
            return resolveTextModel(modelField);
        } catch (HttpErrorException e) {
            return null;
        }
    }

    /** 选号（raw 透传回退用）。 */
    public AccountScheduler.SelectedAccount selectAccount(CatalogModelRepository.Row model, Set<UUID> tried) {
        return accountScheduler.select(model, tried);
    }

    /** 释放账号（raw 透传回退用）。 */
    public void releaseAccount(UUID accountId) {
        accountScheduler.release(accountId);
    }

    private CatalogModelRepository.Row resolveTextModel(String modelField) {
        UUID catalogId;
        try {
            catalogId = UUID.fromString(modelField.trim());
        } catch (IllegalArgumentException e) {
            throw new HttpErrorException(400, "MODEL_NOT_FOUND", "未找到文本模型配置");
        }
        CatalogModelRepository.Row model = catalogModelService.resolve(catalogId).orElse(null);
        if (model == null) {
            throw new HttpErrorException(400, "MODEL_NOT_FOUND", "未找到文本模型配置");
        }
        if (!"text".equals(model.type())) {
            throw new HttpErrorException(400, "MODEL_NOT_FOUND", "模型不是文本模型");
        }
        if (model.enabled() == null || !model.enabled()) {
            throw new HttpErrorException(400, "MODEL_NOT_FOUND", "模型已禁用，请联系管理员");
        }
        return model;
    }

    private static boolean supportsNativeWebSearch(String protocol) {
        return "openai-responses".equals(protocol) || "anthropic-messages".equals(protocol)
                || "google-gemini".equals(protocol);
    }

    private void recordSuccess(AccountScheduler.SelectedAccount selected) {
        accountService.findById(selected.accountId()).ifPresent(accountHealthService::recordSuccess);
    }

    private void recordFailure(AccountScheduler.SelectedAccount selected,
                               AccountHealthService.ErrorKind kind, String message) {
        accountService.findById(selected.accountId())
                .ifPresent(row -> accountHealthService.recordFailure(row, kind, message));
    }

    private AccountHealthService.ErrorKind classifyStatus(int status) {
        if (status == 401) {
            return AccountHealthService.ErrorKind.UNAUTHORIZED;
        }
        if (status == 429) {
            return AccountHealthService.ErrorKind.RATE_LIMITED;
        }
        if (status >= 500) {
            return AccountHealthService.ErrorKind.SERVER_ERROR;
        }
        return AccountHealthService.ErrorKind.REJECTED;
    }

    private String normalizeError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return truncate(message, 200);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String text(JsonNode body, String key) {
        if (body == null || !body.has(key) || !body.get(key).isTextual()) {
            return null;
        }
        return body.get(key).asText();
    }

    private static List<String> textArray(JsonNode body, String key) {
        List<String> result = new ArrayList<>();
        if (body != null && body.has(key) && body.get(key).isArray()) {
            body.get(key).forEach(node -> {
                if (node.isTextual()) {
                    result.add(node.asText());
                }
            });
        }
        return result;
    }

    private String parseExtraDescription(String extra) {
        if (extra == null || extra.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(extra);
            if (node != null && node.hasNonNull("description")) {
                return node.get("description").asText();
            }
        } catch (Exception ignored) {
            // 忽略解析失败
        }
        return "";
    }

    private String parseSummaryText(String contextSummary) {
        if (contextSummary == null || contextSummary.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(contextSummary);
            if (node != null && node.hasNonNull("text")) {
                return node.get("text").asText();
            }
        } catch (Exception ignored) {
            // 忽略解析失败
        }
        return null;
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> retryData(int attempt, Object reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attempt", attempt);
        data.put("maxAttempts", MAX_ATTEMPTS);
        data.put("reason", reason == null ? "" : String.valueOf(reason));
        return data;
    }

    private Map<String, Object> errorData(String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message == null ? "模型请求失败" : message);
        data.put("retryable", false);
        return data;
    }
}
