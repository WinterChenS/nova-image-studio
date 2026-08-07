package com.nova.studio.auth;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.nova.studio.rbac.UserPermissionService;
import com.nova.studio.rbac.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * T3.1 (WIN-13) — admin bootstrap: when {@code NOVA_ADMIN_USERNAME} is set in
 * the environment (or .env), promote that user to {@code admin} on startup.
 * Idempotent and a no-op when the user does not exist yet — the operator
 * registers the account first (or sets the var before first login). This is
 * the only way to grant the admin role in P1 (H5: 管理接口 admin 角色); the
 * role is read from the DB on every JWT issuance, so existing sessions see
 * the new role after re-login.
 *
 * <p>WIN-25 (T15, R4/H2) — 提权同时双写 {@code user_roles}（权限判定依据）
 * 并失效权限缓存。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final UserRoleRepository userRoleRepository;
    private final UserPermissionService permissionService;
    private final String adminUsername;

    public AdminBootstrap(UserRepository userRepository, UserMapper userMapper,
                          UserRoleRepository userRoleRepository, UserPermissionService permissionService,
                          @Value("${NOVA_ADMIN_USERNAME:}") String adminUsername) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.userRoleRepository = userRoleRepository;
        this.permissionService = permissionService;
        this.adminUsername = adminUsername == null ? "" : adminUsername.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminUsername.isEmpty()) {
            return; // no admin configured — admin API stays closed
        }
        try {
            if (!userRepository.existsByUsername(adminUsername)) {
                log.info("[admin-bootstrap] 用户 {} 尚未注册，跳过提权（注册后重启或重设变量生效）", adminUsername);
                return;
            }
            // Plain UpdateWrapper (string columns) — LambdaUpdateWrapper needs the
            // MyBatis-Plus runtime lambda cache, which is unavailable in unit tests.
            userMapper.update(null, new UpdateWrapper<UserEntity>()
                    .eq("username", adminUsername)
                    .set("role", "admin")
                    .set("updated_at", Instant.now()));
            userRepository.findByUsername(adminUsername).ifPresent(row -> {
                // T15 (R4): 双写 user_roles + 失效权限缓存（A14 即时生效）
                userRoleRepository.assignRole(row.id(), "admin");
                permissionService.invalidate(row.id());
            });
            log.info("[admin-bootstrap] 用户 {} 已提升为 admin", adminUsername);
        } catch (Exception e) {
            log.warn("[admin-bootstrap] 提权失败: {}", e.getMessage());
        }
    }
}
