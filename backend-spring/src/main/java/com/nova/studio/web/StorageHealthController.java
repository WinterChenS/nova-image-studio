package com.nova.studio.web;

import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.storage.ObjectStorageManager;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WIN-22 (F-35) — {@code GET /api/nova/storage/health}: MinIO connectivity,
 * bucket existence and the active mode (minio/disk). Returns no credentials
 * (ADR-20); the settings page renders the status card from this payload.
 */
@RestController
@RequestMapping("/api/nova/storage")
public class StorageHealthController {

    private final ObjectStorageManager storageManager;

    public StorageHealthController(ObjectStorageManager storageManager) {
        this.storageManager = storageManager;
    }

    @GetMapping("/health")
    public Map<String, Object> health(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        ObjectStorageManager.StorageHealthInfo info = storageManager.health();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", info.mode());
        body.put("minioConfigured", info.minioConfigured());
        body.put("bucket", info.bucket());
        body.put("bucketExists", info.bucketExists());
        body.put("endpoint", info.endpoint());
        body.put("reachable", info.reachable());
        body.put("fallbackActive", info.fallbackActive());
        body.put("lastCheckedAt", info.lastCheckedAt());
        return body;
    }
}
