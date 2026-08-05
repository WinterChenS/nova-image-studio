package com.nova.studio.web;

import com.nova.studio.auth.AuthFilter;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.settings.ModelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Model registry API (T2.1) — {@code /api/nova/models} CRUD, per-user isolated
 * (T2.2). All endpoints require login; responses carry masked keys
 * ({@code sk-***last4}).
 */
@RestController
@RequestMapping("/api/nova/models")
public class ModelsController {

    private final ModelService modelService;

    public ModelsController(ModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        AuthUser user = AuthFilter.require(request);
        return modelService.list(user.id());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body, HttpServletRequest request) {
        AuthUser user = AuthFilter.require(request);
        Map<String, Object> model = modelService.create(user.id(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(model);
    }

    @PutMapping("/{modelId}")
    public Map<String, Object> update(@PathVariable String modelId, @RequestBody JsonNode body, HttpServletRequest request) {
        AuthUser user = AuthFilter.require(request);
        return modelService.update(user.id(), parseId(modelId), body);
    }

    @DeleteMapping("/{modelId}")
    public Map<String, Object> delete(@PathVariable String modelId, HttpServletRequest request) {
        AuthUser user = AuthFilter.require(request);
        modelService.delete(user.id(), parseId(modelId));
        return Map.of("ok", true);
    }

    private static UUID parseId(String modelId) {
        try {
            return UUID.fromString(modelId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("模型 ID 无效");
        }
    }
}
