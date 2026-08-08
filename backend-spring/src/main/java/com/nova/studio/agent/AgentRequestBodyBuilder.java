package com.nova.studio.agent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * WIN-39 (WIN-41 T9/T10) — Agent 请求体构造器：后端组装 LLM 上下文（system 指令 +
 * 图片目录 + 可用图像模型目录）并构造 4 协议请求体，语义镜像前端
 * {@code agent-chat-client.ts} 的 {@code buildInstructions} /
 * {@code buildAgentRequestBody} + {@code agent-chat-config.ts} 的指令/工具 schema
 * （前端不再传协议与完整历史，T9 会话托管）。
 */
public class AgentRequestBodyBuilder {

    /** 系统指令（迁移自 agent-chat-config.ts AGENT_SYSTEM_INSTRUCTIONS）。 */
    public static final String AGENT_SYSTEM_INSTRUCTIONS = """
            你是一个图像生成与编辑助手，全程使用简体中文与用户自然对话。

            你的能力：
            - 与用户连续对话，帮助澄清他们想要的画面。
            - 当你判断用户想要「生成一张新图」或「修改已有图片」时，调用 propose_image_action 工具，把建议的提示词和参考图交给用户确认，而不是把提示词直接写进聊天回复里。

            关于图片目录：
            - 每轮对话我都会在指令里附上一份「当前可用图片目录」，列出每张图片的 id（如 img_1）和文字描述。
            - 这是你唯一能看到的图片信息来源（你看不到图片本身，只能靠描述判断）。
            - 当用户提到「这张图 / 刚才那张 / 把它改成…」时，结合最近对话和目录描述推断他指的是哪个 id。

            调用 propose_image_action 的规则：
            - action="generate"：从零生成新图。一般 referenced_image_ids 为空；若用户希望参考已有图片的风格/主体，可放入相关 id。
            - action="edit"：在已有图片基础上修改。referenced_image_ids 必须从目录里挑出要参考或被修改的图片 id，支持多张。
            - prompt 要写成一段完整、可直接用于绘图模型的高质量中文提示词，聚焦于用户想要的画面效果、风格和修改意图。
            - ⚠️ 禁止在 prompt 中描述参考图的具体内容（如"一只橘猫""蓝色天空"等），因为图片模型本身支持图片输入，文字描述反而会干扰模型对图片的理解。请在 prompt 中用"图1""图2""图3"指代参考图，编号按 referenced_image_ids 数组顺序（第1个=图1，第2个=图2）。例如：写"参考图1的风格，将主体替换为图2中的建筑"而非"参考一张有蓝色天空和橘猫的图片"。
            - reason 用一句话向用户说明你的判断（例如「你想把这张橘猫的帽子换成红色，我建议这样改」）。

            关于生图参数（你只给「语义建议」，系统会按用户当前选择的图像模型自动合法化，你不用关心具体像素或某个模型支不支持）：
            - requested_aspect_ratio：只有当用户用语言明确表达了画面比例或方向时才填，否则给 null。横屏类填 "16:9"，竖屏/手机屏填 "9:16"，正方形填 "1:1"，可用 "w:h" 形式（如 "3:2"、"4:5"）。这是最高优先级。
            - suggested_aspect_ratio：无论用户是否说过，都给一个你认为最合适的比例（如肖像给 "2:3"、风景给 "16:9"、图标给 "1:1"）。当用户没明确指定、也没有可参考的上传图时作为兜底。
            - requested_output_size：只有当用户明确要求清晰度/分辨率档位时才填，取值 "512"/"1K"/"2K"/"4K"/"auto" 之一，否则给 null。
            - temperature：用户表达「更随机/更有创意」给偏高值（接近 2），「更精确/更稳定」给偏低值（接近 0），无明确倾向给 1 或 null。
            - parallel_count：用户要「多出几张/多个方案」时给 2-4，否则给 1 或 null。
            - gpt_image_quality：当用户明确要求 GPT Image 2 的质量档位时填 "high"/"medium"/"low"，无明确需求给 "auto" 或 null。
            - gpt_image_style：当用户明确要求鲜明、夸张、强表现力时填 "vivid"；要求自然、写实时填 "natural"；无明确需求给 null。
            - gpt_image_background：用户明确要求透明背景、抠图、无背景时填 "transparent"；明确要求实底/不透明时填 "opaque"；否则给 "auto" 或 null。
            - 不要假设具体像素尺寸，也不要因为某个比例「可能不被支持」而回避；系统会自动贴合到最近的合法比例与档位。

            关于模型选择：
            - 每轮对话我也会在指令里附上一份「当前可用图像模型」列表，每项包含模型 id、名称和最大分辨率。
            - requested_model_id：只有当用户用语言明确指定了某个模型时才填对应的 id（如用户说「用 Banana Pro」则填该模型的 id），否则给 null。系统会自动验证该 id 是否有效。
            - 如果用户要求了某个分辨率档位（如「画一张4K的图」），填 requested_output_size，系统会自动选择支持该分辨率的模型，你不需要判断模型是否支持。
            - 如果用户既没指定模型也没指定分辨率，requested_model_id 给 null，系统会使用当前已选模型。

            什么时候不要调用工具：
            - 纯闲聊、提问、澄清需求时，正常用文字回答。
            - 信息不足、拿不准用户到底要不要画图时，先用文字追问，不要急着调用工具。

            注意：最终是否执行由用户在确认面板里决定，用户可以修改你的提示词、增删参考图，或直接取消。""";

    /** 视觉描述提示词（迁移自 agent-chat-config.ts）。 */
    public static final String AGENT_IMAGE_DESCRIBE_PROMPT =
            "用一到两句简体中文描述这张图片，覆盖主体、风格、主要颜色和关键元素，便于后续判断是否复用它作为参考图。只输出描述本身，不要任何前缀、解释或标点装饰。";

    /** 工具 schema（迁移自 agent-chat-config.ts PROPOSE_IMAGE_ACTION_TOOL）。 */
    public static final String TOOL_NAME = "propose_image_action";

    public static final String TOOL_DESCRIPTION =
            "当判断用户想要生成新图或修改已有图片时调用，提交一份生图/改图提案交给用户确认。";

    /** 内部历史消息（角色 + 文本）。 */
    public record HistoryTurn(String id, String role, String text) {
        public HistoryTurn(String role, String text) {
            this(null, role, text);
        }
    }

    /** 图片目录项。 */
    public record CatalogEntry(String imgId, String description) {
    }

    /** 可用图像模型目录项。 */
    public record ModelEntry(String id, String name, String maxOutputSize) {
    }

    private final ObjectMapper objectMapper;

    public AgentRequestBodyBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 组装 system 指令（镜像 buildInstructions）。 */
    public String buildInstructions(List<CatalogEntry> catalog, List<ModelEntry> modelCatalog) {
        StringBuilder instructions = new StringBuilder(AGENT_SYSTEM_INSTRUCTIONS);
        if (modelCatalog != null && !modelCatalog.isEmpty()) {
            instructions.append("\n\n当前可用图像模型：\n");
            for (ModelEntry m : modelCatalog) {
                instructions.append("- id: ").append(m.id()).append(", 名称: \"")
                        .append(m.name()).append("\", 最大分辨率: ")
                        .append(m.maxOutputSize() == null ? "" : m.maxOutputSize()).append('\n');
            }
        } else {
            instructions.append("\n\n当前可用图像模型：（空，请在设置中配置）");
        }
        if (catalog == null || catalog.isEmpty()) {
            instructions.append("\n\n当前可用图片目录：（空，还没有任何图片）");
        } else {
            instructions.append("\n\n当前可用图片目录：\n");
            for (CatalogEntry entry : catalog) {
                instructions.append('[').append(entry.imgId()).append("] ")
                        .append(entry.description() == null ? "" : entry.description()).append('\n');
            }
        }
        return instructions.toString();
    }

    /**
     * 构造 4 协议请求体（镜像 buildAgentRequestBody）。history 已按「过滤
     * system-note/context-divider/空白」处理（调用方保证）。contextSummary 非空时
     * 作为首条 user 系统上下文注入（ADR-44 压缩摘要）。
     */
    public JsonNode buildRequestBody(String protocol, String model, List<HistoryTurn> history,
                                     String instructions, boolean enableNativeWebSearch,
                                     String contextSummary) {
        if (contextSummary != null && !contextSummary.isBlank() && !history.isEmpty()) {
            List<HistoryTurn> withSummary = new java.util.ArrayList<>(history);
            withSummary.add(0, new HistoryTurn("user",
                    "[以下为已压缩的历史对话摘要]\n" + contextSummary));
            history = withSummary;
        }
        switch (protocol == null ? "" : protocol) {
            case "openai-chat-completions" -> {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("stream", true);
                body.put("reasoning_effort", "high");
                ArrayNode messages = body.putArray("messages");
                ObjectNode system = messages.addObject();
                system.put("role", "system");
                system.put("content", instructions);
                for (HistoryTurn turn : history) {
                    if (skip(turn)) {
                        continue;
                    }
                    ObjectNode m = messages.addObject();
                    m.put("role", "user".equals(turn.role()) ? "user" : "assistant");
                    m.put("content", turn.text());
                }
                body.set("tools", functionToolSchema());
                body.put("tool_choice", "auto");
                return body;
            }
            case "anthropic-messages" -> {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("stream", true);
                body.put("max_tokens", 4096);
                body.put("system", instructions);
                ObjectNode thinking = body.putObject("thinking");
                thinking.put("type", "adaptive");
                thinking.put("display", "summarized");
                ObjectNode outputConfig = body.putObject("output_config");
                outputConfig.put("effort", "high");
                ArrayNode messages = body.putArray("messages");
                for (HistoryTurn turn : history) {
                    if (skip(turn)) {
                        continue;
                    }
                    ObjectNode m = messages.addObject();
                    m.put("role", "user".equals(turn.role()) ? "user" : "assistant");
                    ArrayNode content = m.putArray("content");
                    ObjectNode textPart = content.addObject();
                    textPart.put("type", "text");
                    textPart.put("text", turn.text());
                }
                ArrayNode tools = body.putArray("tools");
                ObjectNode tool = tools.addObject();
                tool.put("name", TOOL_NAME);
                tool.put("description", TOOL_DESCRIPTION);
                tool.set("input_schema", toolParameters());
                if (enableNativeWebSearch) {
                    ObjectNode webSearch = tools.addObject();
                    webSearch.put("type", "web_search_20250305");
                    webSearch.put("name", "web_search");
                    webSearch.put("max_uses", 5);
                }
                return body;
            }
            case "google-gemini" -> {
                ObjectNode body = objectMapper.createObjectNode();
                ArrayNode contents = body.putArray("contents");
                ObjectNode sysMsg = contents.addObject();
                sysMsg.put("role", "user");
                ArrayNode sysParts = sysMsg.putArray("parts");
                sysParts.addObject().put("text", instructions);
                for (HistoryTurn turn : history) {
                    if (skip(turn)) {
                        continue;
                    }
                    ObjectNode m = contents.addObject();
                    m.put("role", "user".equals(turn.role()) ? "user" : "model");
                    m.putArray("parts").addObject().put("text", turn.text());
                }
                ArrayNode tools = body.putArray("tools");
                ObjectNode fnDecls = tools.addObject();
                ArrayNode declarations = fnDecls.putArray("function_declarations");
                ObjectNode decl = declarations.addObject();
                decl.put("name", TOOL_NAME);
                decl.put("description", TOOL_DESCRIPTION);
                decl.set("parameters", toolParameters());
                if (enableNativeWebSearch) {
                    tools.addObject().set("google_search", objectMapper.createObjectNode());
                }
                ObjectNode generationConfig = body.putObject("generationConfig");
                ObjectNode thinkingConfig = generationConfig.putObject("thinkingConfig");
                thinkingConfig.put("thinkingBudget", -1);
                thinkingConfig.put("includeThoughts", true);
                return body;
            }
            default -> {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("stream", true);
                ObjectNode reasoning = body.putObject("reasoning");
                reasoning.put("effort", "medium");
                reasoning.put("summary", "detailed");
                body.put("instructions", instructions);
                ArrayNode tools = body.putArray("tools");
                tools.addObject()
                        .put("type", "function")
                        .put("name", TOOL_NAME)
                        .put("description", TOOL_DESCRIPTION)
                        .set("parameters", toolParameters());
                if (enableNativeWebSearch) {
                    tools.addObject().put("type", "web_search");
                }
                body.put("tool_choice", "auto");
                ArrayNode input = body.putArray("input");
                for (HistoryTurn turn : history) {
                    if (skip(turn)) {
                        continue;
                    }
                    ObjectNode m = input.addObject();
                    m.put("role", "user".equals(turn.role()) ? "user" : "assistant");
                    ArrayNode content = m.putArray("content");
                    ObjectNode textPart = content.addObject();
                    textPart.put("type", "user".equals(turn.role()) ? "input_text" : "output_text");
                    textPart.put("text", turn.text());
                }
                return body;
            }
        }
    }

    /** 视觉描述请求体（镜像 buildSimpleProxyTextRequestBody，image = base64 dataUrl）。 */
    public JsonNode buildDescribeRequestBody(String protocol, String model, String imageDataUrl) {
        switch (protocol == null ? "" : protocol) {
            case "openai-chat-completions" -> {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                ArrayNode messages = body.putArray("messages");
                ObjectNode user = messages.addObject();
                user.put("role", "user");
                ArrayNode content = user.putArray("content");
                content.addObject().put("type", "text").put("text", AGENT_IMAGE_DESCRIBE_PROMPT);
                ObjectNode image = content.addObject();
                image.put("type", "image_url");
                image.putObject("image_url").put("url", imageDataUrl);
                return body;
            }
            case "anthropic-messages" -> {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("max_tokens", 2048);
                ArrayNode messages = body.putArray("messages");
                ObjectNode user = messages.addObject();
                user.put("role", "user");
                ArrayNode content = user.putArray("content");
                content.addObject().put("type", "text").put("text", AGENT_IMAGE_DESCRIBE_PROMPT);
                ObjectNode image = content.addObject();
                image.put("type", "image");
                image.set("source", imageSource(imageDataUrl));
                return body;
            }
            case "google-gemini" -> {
                ObjectNode body = objectMapper.createObjectNode();
                ArrayNode contents = body.putArray("contents");
                ObjectNode user = contents.addObject();
                user.put("role", "user");
                ArrayNode parts = user.putArray("parts");
                parts.addObject().put("text", AGENT_IMAGE_DESCRIBE_PROMPT);
                parts.addObject().set("inline_data", inlineData(imageDataUrl));
                return body;
            }
            default -> {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("instructions", AGENT_IMAGE_DESCRIBE_PROMPT);
                ArrayNode input = body.putArray("input");
                ObjectNode user = input.addObject();
                user.put("role", "user");
                ArrayNode content = user.putArray("content");
                content.addObject().put("type", "input_text").put("text", AGENT_IMAGE_DESCRIBE_PROMPT);
                ObjectNode image = content.addObject();
                image.put("type", "input_image");
                image.putObject("image_url").put("url", imageDataUrl);
                return body;
            }
        }
    }

    /** 从非流式响应提取文本输出（镜像 extractTextOutput）。 */
    public String extractTextOutput(String protocol, JsonNode data) {
        if (data == null) {
            return "";
        }
        switch (protocol == null ? "" : protocol) {
            case "openai-chat-completions" -> {
                JsonNode content = data.path("choices").path(0).path("message").path("content");
                return joinTextContent(content);
            }
            case "anthropic-messages" -> {
                StringBuilder sb = new StringBuilder();
                JsonNode content = data.get("content");
                if (content != null && content.isArray()) {
                    content.forEach(part -> {
                        if ("text".equals(part.path("type").asText())) {
                            sb.append(part.path("text").asText(""));
                        }
                    });
                }
                return sb.toString();
            }
            case "google-gemini" -> {
                StringBuilder sb = new StringBuilder();
                JsonNode candidates = data.get("candidates");
                if (candidates != null && candidates.isArray()) {
                    candidates.forEach(candidate -> {
                        JsonNode parts = candidate.path("content").path("parts");
                        if (parts.isArray()) {
                            parts.forEach(part -> sb.append(part.path("text").asText("")));
                        }
                    });
                }
                return sb.toString();
            }
            default -> {
                JsonNode text = data.get("output_text");
                if (text != null && text.isTextual()) {
                    return text.asText();
                }
                JsonNode output = data.get("output");
                if (output != null && output.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    output.forEach(item -> {
                        if ("message".equals(item.path("type").asText())) {
                            sb.append(item.path("content").path(0).path("text").asText(""));
                        }
                    });
                    return sb.toString();
                }
                return "";
            }
        }
    }

    // ===== helpers =====

    private boolean skip(HistoryTurn turn) {
        if (turn == null || turn.text() == null || turn.text().trim().isEmpty()) {
            return true;
        }
        return "system-note".equals(turn.role()) || "context-divider".equals(turn.role());
    }

    private JsonNode functionToolSchema() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", TOOL_NAME);
        fn.put("description", TOOL_DESCRIPTION);
        fn.set("parameters", toolParameters());
        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(tool);
        return tools;
    }

    private JsonNode toolParameters() {
        // 程序化构造（前端 PROPOSE_IMAGE_ACTION_TOOL.parameters 的等价物，避免内嵌引号 JSON 转义问题）
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = params.putObject("properties");
        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.putArray("enum").add("generate").add("edit");
        action.put("description", "generate=从零生成新图；edit=在已有图片基础上修改");
        properties.putObject("prompt").put("type", "string")
                .put("description", "建议的完整中文绘图提示词。聚焦用户想要的画面效果和修改意图，不要描述参考图的具体内容。用「图1」「图2」指代 referenced_image_ids 中对应位置的参考图");
        properties.putObject("referenced_image_ids")
                .put("type", "array")
                .set("items", objectMapper.createObjectNode().put("type", "string"))
                .put("description", "要参考或被修改的图片 id（来自图片目录），generate 通常为空数组");
        properties.putObject("reason").put("type", "string")
                .put("description", "一句话向用户说明这次提案的判断依据");
        ObjectNode requestedAspect = properties.putObject("requested_aspect_ratio");
        requestedAspect.set("type", stringOrNull());
        requestedAspect.put("description", "用户明确指定的比例/方向时填（横屏=16:9，竖屏=9:16，正方形=1:1，或 w:h），否则 null");
        ObjectNode suggestedAspect = properties.putObject("suggested_aspect_ratio");
        suggestedAspect.set("type", stringOrNull());
        suggestedAspect.put("description", "你推荐的最合适比例（始终尽量给出），无把握时可为 null");
        ObjectNode requestedSize = properties.putObject("requested_output_size");
        requestedSize.set("type", stringOrNull());
        requestedSize.putArray("enum").add("512").add("1K").add("2K").add("4K").add("auto").addNull();
        requestedSize.put("description", "用户明确要求的清晰度档位，否则 null");
        ObjectNode temperature = properties.putObject("temperature");
        temperature.set("type", numberOrNull());
        temperature.put("description", "建议温度 0-2，无明确倾向给 null");
        ObjectNode parallelCount = properties.putObject("parallel_count");
        parallelCount.set("type", integerOrNull());
        parallelCount.put("description", "建议并行生成数量 1-4，无明确需求给 null");
        ObjectNode gptQuality = properties.putObject("gpt_image_quality");
        gptQuality.set("type", stringOrNull());
        gptQuality.putArray("enum").add("auto").add("high").add("medium").add("low").addNull();
        gptQuality.put("description", "GPT Image 2 质量参数；无明确需求给 auto 或 null");
        ObjectNode gptStyle = properties.putObject("gpt_image_style");
        gptStyle.set("type", stringOrNull());
        gptStyle.putArray("enum").add("vivid").add("natural").addNull();
        gptStyle.put("description", "GPT Image 2 风格参数；自动时给 null");
        ObjectNode gptBackground = properties.putObject("gpt_image_background");
        gptBackground.set("type", stringOrNull());
        gptBackground.putArray("enum").add("auto").add("transparent").add("opaque").addNull();
        gptBackground.put("description", "GPT Image 2 背景参数；无明确需求给 auto 或 null");
        properties.putObject("requested_model_id").set("type", stringOrNull())
                .put("description", "用户明确指定的模型 id（来自当前可用图像模型列表），否则 null");
        ArrayNode required = params.putArray("required");
        for (String key : List.of("action", "prompt", "referenced_image_ids", "reason",
                "requested_aspect_ratio", "suggested_aspect_ratio", "requested_output_size",
                "temperature", "parallel_count", "gpt_image_quality", "gpt_image_style",
                "gpt_image_background", "requested_model_id")) {
            required.add(key);
        }
        params.put("additionalProperties", false);
        return params;
    }

    private ArrayNode stringOrNull() {
        return objectMapper.createArrayNode().add("string").addNull();
    }

    private ArrayNode numberOrNull() {
        return objectMapper.createArrayNode().add("number").addNull();
    }

    private ArrayNode integerOrNull() {
        return objectMapper.createArrayNode().add("integer").addNull();
    }

    private String joinTextContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            content.forEach(part -> {
                if ("text".equals(part.path("type").asText())) {
                    sb.append(part.path("text").asText(""));
                }
            });
            return sb.toString();
        }
        return "";
    }

    /** dataUrl "data:<mime>;base64,<b64>" → {mimeType, data}。 */
    public static String[] splitDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            return new String[]{"image/png", dataUrl == null ? "" : dataUrl};
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            return new String[]{"image/png", dataUrl};
        }
        String head = dataUrl.substring(5, comma);
        String body = dataUrl.substring(comma + 1);
        int semi = head.indexOf(';');
        String mime = semi > 0 ? head.substring(0, semi) : "image/png";
        return new String[]{mime, body};
    }

    private ObjectNode imageSource(String dataUrl) {
        String[] parts = splitDataUrl(dataUrl);
        ObjectNode source = objectMapper.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", parts[0]);
        source.put("data", parts[1]);
        return source;
    }

    private ObjectNode inlineData(String dataUrl) {
        String[] parts = splitDataUrl(dataUrl);
        ObjectNode inline = objectMapper.createObjectNode();
        inline.put("mime_type", parts[0]);
        inline.put("data", parts[1]);
        return inline;
    }
}
