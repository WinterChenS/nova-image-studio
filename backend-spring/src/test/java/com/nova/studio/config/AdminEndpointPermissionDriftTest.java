package com.nova.studio.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * WIN-30 (T14, 质量门禁 Part J.2) — 「admin 端点均有权限注解」防漂移单测：
 * <ol>
 *   <li>扫描 {@code com.nova.studio.web} 下所有控制器，凡映射路径落在
 *       {@code /api/nova/admin/**}（含 {@code /api/nova/proxy/models} 管理探针）
 *       的方法都必须有 {@code @PreAuthorize}；</li>
 *   <li>注解引用的 {@code PERM_} 码必须存在于 V6 种子（解析
 *       {@code V6__seed_rbac.sql} 的 permissions INSERT），防止权限码漂移；</li>
 *   <li>该权限码在 V6 中的 {@code api_path} 必须覆盖该端点路径（前缀/通配匹配），
 *       防止「端点换挂到别的权限码」式漂移（G.2 api_path 用途 ②）。</li>
 * </ol>
 */
class AdminEndpointPermissionDriftTest {

    private static final Pattern PERM_CODE = Pattern.compile("PERM_([a-z0-9.]+)");
    private static final Set<Class<? extends Annotation>> MAPPING_ANNOTATIONS = Set.of(
            GetMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    /** V6 种子解析结果：code → api_path（来自 permissions INSERT）。 */
    private Map<String, String> seedPermissions() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        String sql;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V6__seed_rbac.sql")) {
            assertThat(in).as("V6 迁移脚本必须存在").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 V6__seed_rbac.sql 失败", e);
        }
        // 解析 permissions INSERT ... VALUES ('code','type','parent','label','api_path',sort), ...
        Matcher values = Pattern.compile(
                "INSERT INTO permissions[^;]*?VALUES\\s*(.*?);", Pattern.DOTALL).matcher(sql);
        while (values.find()) {
            String block = values.group(1);
            for (String tuple : splitTuples(block)) {
                List<String> cells = parseCells(tuple);
                if (cells.size() >= 5) {
                    result.put(unquote(cells.get(0)), unquote(cells.get(4)));
                }
            }
        }
        assertThat(result).as("V6 必须包含 13 个权限码种子").hasSize(13);
        return result;
    }

    /** 按「'...' 或 NULL」单元格切分 INSERT 元组。 */
    private static List<String> splitTuples(String block) {
        List<String> tuples = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                tuples.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.toString().trim().length() > 0) tuples.add(cur.toString().trim());
        return tuples;
    }

    private static List<String> parseCells(String tuple) {
        List<String> cells = new ArrayList<>();
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < tuple.length(); i++) {
            char c = tuple.charAt(i);
            if (c == '\'' && (i == 0 || tuple.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
                cur.append(c);
            } else if (c == ',' && !inQuote) {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.toString().trim().length() > 0) cells.add(cur.toString().trim());
        return cells;
    }

    private static String unquote(String cell) {
        String v = cell.trim();
        if (v.startsWith("'") && v.endsWith("'")) return v.substring(1, v.length() - 1);
        return v;
    }

    @Test
    void everyAdminEndpointHasPreAuthorizeWithSeededPermission() throws Exception {
        Map<String, String> seed = seedPermissions();
        List<String> failures = new ArrayList<>();

        for (Class<?> clazz : scanControllers()) {
            String classPath = classRequestMapping(clazz);
            for (Method method : clazz.getDeclaredMethods()) {
                String methodPath = methodRequestMapping(method);
                if (methodPath == null) continue; // 非端点方法
                String fullPath = join(classPath, methodPath);
                if (!isAdminPath(fullPath)) continue;

                PreAuthorize pre = method.getAnnotation(PreAuthorize.class);
                if (pre == null) {
                    failures.add(fullPath + " 缺少 @PreAuthorize");
                    continue;
                }
                Matcher m = PERM_CODE.matcher(pre.value());
                if (!m.find()) {
                    failures.add(fullPath + " 注解无 PERM_ 码: " + pre.value());
                    continue;
                }
                String code = m.group(1);
                String apiPath = seed.get(code);
                if (apiPath == null) {
                    failures.add(fullPath + " 引用了未种子的权限码 " + code);
                    continue;
                }
                if (!covers(apiPath, fullPath)) {
                    failures.add(fullPath + " 的权限码 " + code + " api_path(" + apiPath + ") 不覆盖该端点");
                }
            }
        }
        assertThat(failures).as("admin 端点权限注解防漂移").isEmpty();
    }

    /** 控制器扫描：com.nova.studio.web 下所有带 @RequestMapping 的类。 */
    private List<Class<?>> scanControllers() throws Exception {
        List<Class<?>> controllers = new ArrayList<>();
        String pkg = "com.nova.studio.web";
        String path = pkg.replace('.', '/');
        java.net.URL url = getClass().getClassLoader().getResource(path);
        if (url == null) return controllers;
        java.io.File dir = new java.io.File(url.toURI());
        for (java.io.File file : dir.listFiles((d, name) -> name.endsWith(".class"))) {
            String className = pkg + "." + file.getName().replace(".class", "");
            Class<?> clazz = Class.forName(className);
            if (clazz.getAnnotation(RequestMapping.class) != null) {
                controllers.add(clazz);
            }
        }
        return controllers;
    }

    private static String classRequestMapping(Class<?> clazz) {
        RequestMapping rm = clazz.getAnnotation(RequestMapping.class);
        return rm == null ? "" : rm.value().length > 0 ? rm.value()[0] : "";
    }

    private static String methodRequestMapping(Method method) throws Exception {
        for (Class<? extends Annotation> ann : MAPPING_ANNOTATIONS) {
            Annotation a = method.getAnnotation(ann);
            if (a == null) continue;
            String[] values = (String[]) a.annotationType().getMethod("value").invoke(a);
            return values.length > 0 ? values[0] : "";
        }
        return null;
    }

    private static String join(String classPath, String methodPath) {
        String base = classPath == null || classPath.isEmpty() ? "" : classPath;
        String sub = methodPath == null || methodPath.isEmpty() ? "" : methodPath;
        if (base.isEmpty()) return sub;
        if (sub.isEmpty()) return base;
        return base + (sub.startsWith("/") ? sub : "/" + sub);
    }

    /** 管理端点判定：/api/nova/admin/** 或管理探针 /api/nova/proxy/models。 */
    private static boolean isAdminPath(String path) {
        return path.startsWith("/api/nova/admin")
                || path.equals("/api/nova/proxy/models");
    }

    /** api_path 覆盖判定：相等、前缀（子路径）、或含 * 通配段；菜单权限无 api_path 不强制。 */
    private static boolean covers(String apiPath, String endpointPath) {
        if (apiPath == null || apiPath.isBlank()) return true; // 如 admin.console.view（菜单权限无 api_path）
        if (endpointPath.equals(apiPath)) return true;
        if (apiPath.endsWith("/**") && endpointPath.startsWith(apiPath.substring(0, apiPath.length() - 3))) {
            return true;
        }
        // 通配段（如 /api/nova/admin/accounts/*/test）：逐段匹配
        String[] apiSegs = apiPath.split("/");
        String[] epSegs = endpointPath.split("/");
        if (apiSegs.length != epSegs.length) {
            // 前缀覆盖：api_path 是端点路径的父路径（如 /api/nova/admin/accounts 覆盖 POST /api/nova/admin/accounts）
            return endpointPath.startsWith(apiPath.endsWith("/") ? apiPath : apiPath + "/");
        }
        for (int i = 0; i < apiSegs.length; i++) {
            if (apiSegs[i].equals("*")) continue;
            if (!apiSegs[i].equals(epSegs[i])) return false;
        }
        return true;
    }
}
