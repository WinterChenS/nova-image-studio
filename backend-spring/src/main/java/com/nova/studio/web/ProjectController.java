package com.nova.studio.web;

import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.project.ProjectRepository;
import com.nova.studio.project.ProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WIN-22 (D.1) — per-user project API:
 * {@code GET/POST /api/nova/projects}, {@code PUT/DELETE /api/nova/projects/{id}},
 * {@code POST /api/nova/projects/{id}/default}. All routes require login;
 * ownership is enforced in {@link ProjectService} (cross-user → 404, N-1).
 */
@RestController
@RequestMapping("/api/nova/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean includeArchived,
                                          @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProjectService.ProjectDetail detail : projectService.listByUser(authUser.id(), includeArchived)) {
            result.add(ProjectService.toJson(detail));
        }
        return result;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body,
                                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        String name = body.hasNonNull("name") ? body.get("name").asText() : null;
        String description = body.hasNonNull("description") ? body.get("description").asText() : null;
        Integer sortOrder = body.hasNonNull("sortOrder") && body.get("sortOrder").isNumber()
                ? body.get("sortOrder").asInt() : null;
        var row = projectService.create(authUser.id(), name, description, sortOrder);
        return ResponseEntity.status(201).body(projectJson(row));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody JsonNode body,
                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        String name = body.hasNonNull("name") ? body.get("name").asText() : null;
        String description = body.hasNonNull("description") ? body.get("description").asText() : null;
        Boolean archived = body.hasNonNull("archived") ? body.get("archived").asBoolean() : null;
        Integer sortOrder = body.hasNonNull("sortOrder") && body.get("sortOrder").isNumber()
                ? body.get("sortOrder").asInt() : null;
        Boolean autoSave = body.hasNonNull("autoSave") ? body.get("autoSave").asBoolean() : null;
        var row = projectService.update(authUser.id(), id, name, description, archived, sortOrder, autoSave);
        return projectJson(row);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id,
                                      @RequestParam(defaultValue = "false") boolean force,
                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        projectService.delete(authUser.id(), id, force);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/default")
    public Map<String, Object> setDefault(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        projectService.setDefault(authUser.id(), id);
        return Map.of("ok", true);
    }

    private Map<String, Object> projectJson(ProjectRepository.ProjectRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("name", row.name());
        map.put("description", row.description());
        map.put("archived", row.archived());
        map.put("sortOrder", row.sortOrder());
        map.put("autoSave", row.autoSave());
        map.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        map.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
        return map;
    }
}
