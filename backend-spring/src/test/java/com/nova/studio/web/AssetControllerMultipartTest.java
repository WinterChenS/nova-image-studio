package com.nova.studio.web;

import com.nova.studio.asset.AssetService;
import com.nova.studio.asset.AssetRepository;
import com.nova.studio.auth.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA B-2 回归 — multipart 素材上传：
 * Spring Boot 4.1 会把 multipart Content-Type 归一为带 {@code charset=UTF-8}，
 * 声明 {@code consumes = multipart/form-data} 会 400。控制器已改为不声明
 * consumes + 按内容类型手工分发 —— 本测试用真实 MockMvc multipart（等价浏览器
 * FormData 路径）验证：带 charset 参数的 multipart 上传必须 201，JSON text 上传必须 201。
 */
@WebMvcTest(AssetController.class)
@AutoConfigureMockMvc(addFilters = false)
class AssetControllerMultipartTest {

    private static final AuthUser USER = new AuthUser(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "alice", "user");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AssetService assetService;

    // StaticResourceFilter 是 @Component，切片会实例化它 → 补齐依赖
    @MockitoBean
    private StaticFileResolver staticFileResolver;

    private void loginAsUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER, null));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void multipartUploadSucceedsEvenWithCharsetParameter() throws Exception {
        loginAsUser();
        AssetRepository.AssetRow row = imageRow();
        // 可空字段（note/sourceLabel/sourceRef/prompt）为 null —— any() 匹配，anyString() 不匹配
        when(assetService.createImage(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(row);

        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});

        // 模拟浏览器 FormData 的 Content-Type：multipart/form-data; boundary=...（含 charset 变体）
        mvc.perform(multipart("/api/nova/assets")
                        .file(file)
                        .param("projectId", "p1")
                        .param("sourceKind", "upload")
                        .param("name", "测试图")
                        .contentType("multipart/form-data; charset=UTF-8"))
                .andExpect(status().isCreated())
                .andReturn();

        verify(assetService).createImage(any(), eq("p1"), eq("测试图"), any(), any(),
                eq("upload"), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void jsonTextAssetSucceeds() throws Exception {
        loginAsUser();
        AssetRepository.AssetRow row = new AssetRepository.AssetRow(
                "t1", USER.id().toString(), "p1", "text", "提示词", null, 10L, null, null,
                "[]", null, "manual", "手动导入", null, null, null, "text-abc",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"), null);
        when(assetService.createText(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(row);

        mvc.perform(post("/api/nova/assets")
                        .contentType("application/json")
                        .content("{\"content\":\"一个可爱的猫\",\"projectId\":\"p1\",\"sourceKind\":\"manual\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    @Test
    void multipartWithoutFileRejected400() throws Exception {
        loginAsUser();
        // 未携带 file 部分的 multipart 请求 → 控制器拒绝（而非 415/内容类型错误）
        mvc.perform(multipart("/api/nova/assets")
                        .param("sourceKind", "upload"))
                .andExpect(status().isBadRequest());
    }

    private static AssetRepository.AssetRow imageRow() {
        return new AssetRepository.AssetRow(
                "a1", USER.id().toString(), "p1", "image", "测试图", "image/png", 3L, 1, 1,
                "[]", null, "upload", "用户上传", null, null, "assets/u1/a1.png", "abc",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"), null);
    }
}
