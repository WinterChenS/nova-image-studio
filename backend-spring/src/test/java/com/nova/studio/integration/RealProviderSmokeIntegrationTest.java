package com.nova.studio.integration;

import com.nova.studio.auth.UserEntity;
import com.nova.studio.auth.UserMapper;
import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.settings.CryptoService;
import com.nova.studio.settings.ModelEntity;
import com.nova.studio.settings.ModelMapper;
import com.nova.studio.task.TaskRequest;
import com.nova.studio.textproxy.TextProxyService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3.4 (WIN-13) — real-provider integration smoke (PRD F-13): proves the
 * Spring backend can reach the actual AI providers with the keys the user
 * configured in the DB via the settings API.
 *
 * <p><b>Gating / safety:</b>
 * <ul>
 *   <li>Opt-in only: {@code NOVA_REAL_PROVIDER_TESTS=true} (never set in CI —
 *       the normal pipeline uses mock-upstream A/B diff instead).</li>
 *   <li>Account under test: {@code NOVA_IT_USERNAME} (an account the user
 *       configured models for). Models are read from the DB and the key is
 *       decrypted in-process exactly like the app does at runtime; the
 *       plaintext key is never printed, persisted or returned.</li>
 *   <li>When the account has no keyed models the tests are skipped with a
 *       clear reason (the framework is ready, keys pending).</li>
 * </ul>
 *
 * <p>Each image model generates one tiny image (parallelCount=1, minimal
 * prompt); each text model forwards one short chat prompt. Results are
 * printed so the integration-test report can quote them.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "NOVA_REAL_PROVIDER_TESTS", matches = "(?i)true")
class RealProviderSmokeIntegrationTest {

    private static final String USERNAME = System.getenv("NOVA_IT_USERNAME");

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ModelMapper modelMapper;
    @Autowired
    private CryptoService cryptoService;
    @Autowired
    private ImageGenService imageGenService;
    @Autowired
    private TextProxyService textProxyService;

    private static UUID userId;
    private static List<ModelEntity> imageModels = new ArrayList<>();
    private static List<ModelEntity> textModels = new ArrayList<>();

    @BeforeAll
    static void loadConfiguredModels() {
        Assumptions.assumeTrue(USERNAME != null && !USERNAME.isBlank(),
                "NOVA_IT_USERNAME 未设置 — 跳过真实提供商测试");
    }

    @Test
    void imageModelsReachRealProvider() {
        userId = userMapper.selectList(null).stream()
                .filter(u -> USERNAME.equals(u.getUsername()))
                .findFirst().map(UserEntity::getId).orElse(null);
        Assumptions.assumeTrue(userId != null, "用户 " + USERNAME + " 不存在 — 请先注册该账号");
        imageModels = modelMapper.selectList(null).stream()
                .filter(m -> userId.equals(m.getUserId()) && "image".equals(m.getType()))
                .filter(this::hasKey)
                .toList();
        Assumptions.assumeTrue(!imageModels.isEmpty(),
                "用户 " + USERNAME + " 未配置带 API Key 的图片模型 — 请先通过设置接口配置后再运行");

        for (ModelEntity model : imageModels) {
            String result = invokeImageModel(model);
            System.out.println("[real-provider] image OK model=" + model.getName()
                    + " protocol=" + model.getProtocol() + " modelId=" + model.getModelId()
                    + " resultHead=" + head(result));
            assertThat(result).isNotBlank();
        }
    }

    @Test
    void textModelsReachRealProvider() {
        userId = userMapper.selectList(null).stream()
                .filter(u -> USERNAME.equals(u.getUsername()))
                .findFirst().map(UserEntity::getId).orElse(null);
        Assumptions.assumeTrue(userId != null, "用户 " + USERNAME + " 不存在 — 请先注册该账号");
        textModels = modelMapper.selectList(null).stream()
                .filter(m -> userId.equals(m.getUserId()) && "text".equals(m.getType()))
                .filter(this::hasKey)
                .toList();
        Assumptions.assumeTrue(!textModels.isEmpty(),
                "用户 " + USERNAME + " 未配置带 API Key 的文本模型 — 请先通过设置接口配置后再运行");

        for (ModelEntity model : textModels) {
            String reply = invokeTextModel(model);
            System.out.println("[real-provider] text OK model=" + model.getName()
                    + " protocol=" + model.getProtocol() + " modelId=" + model.getModelId()
                    + " replyHead=" + head(reply));
            assertThat(reply).isNotBlank();
        }
    }

    // ===== helpers =====

    private boolean hasKey(ModelEntity model) {
        if (model.getApiKeyEnc() == null || model.getApiKeyEnc().isBlank()) {
            return false;
        }
        try {
            return !cryptoService.decrypt(model.getApiKeyEnc()).isBlank();
        } catch (Exception e) {
            System.out.println("[real-provider] WARN 模型 " + model.getName() + " 密钥解密失败，跳过: " + e.getMessage());
            return false;
        }
    }

    private String invokeImageModel(ModelEntity model) {
        TaskRequest request = new TaskRequest(
                "text-to-image", model.getProtocol(), model.getBaseUrl(), "a single red cube on white",
                null, null, null, null, model.getModelId(),
                null, null, null, 1, List.of());
        return imageGenService.generate(model.getProtocol(), cryptoService.decrypt(model.getApiKeyEnc()), request);
    }

    private String invokeTextModel(ModelEntity model) {
        TextProxyService.Target target = textProxyService.buildTarget(
                model.getProtocol(), model.getBaseUrl(), cryptoService.decrypt(model.getApiKeyEnc()),
                model.getModelId(), false);
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.JsonNode body = mapper.createObjectNode()
                .put("messages", "[]")
                .set("messages", mapper.createArrayNode().add(
                        mapper.createObjectNode().put("role", "user").put("content", "Say OK")));
        TextProxyService.ProxyExchange exchange = textProxyService.exchange(target, body);
        assertThat(exchange.status()).isBetween(200, 299);
        return exchange.jsonBody();
    }

    private static String head(String value) {
        if (value == null) {
            return "null";
        }
        String flat = value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
