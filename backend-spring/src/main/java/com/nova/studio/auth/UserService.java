package com.nova.studio.auth;

import com.nova.studio.infra.HttpErrorException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Registration / login orchestration (T2.2). Passwords are bcrypt-hashed
 * (cost 12 per ADR C.6 security); successful login issues a JWT.
 */
@Service
public class UserService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]{2,32}$");

    private final UserRepository repository;
    private final BCryptPasswordEncoder encoder;
    private final JwtService jwtService;

    public UserService(UserRepository repository, JwtService jwtService) {
        this.repository = repository;
        this.jwtService = jwtService;
        this.encoder = new BCryptPasswordEncoder(12);
    }

    /** Node-style register: username/password validation, unique check, insert. */
    public Map<String, Object> register(String username, String password) {
        String user = normalize(username, "用户名不能为空");
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码长度至少 6 位");
        }
        if (password.length() > 72) {
            throw new IllegalArgumentException("密码长度不能超过 72 位");
        }
        if (!USERNAME_PATTERN.matcher(user).matches()) {
            throw new IllegalArgumentException("用户名仅支持 2-32 位字母、数字、下划线、短横线或中文");
        }
        if (repository.existsByUsername(user)) {
            throw new HttpErrorException(409, "USERNAME_TAKEN", "用户名已被占用");
        }
        UUID id = repository.insert(user, encoder.encode(password), "user");
        return publicUser(id, user, "user");
    }

    /** Login: verify credentials, issue JWT. */
    public Map<String, Object> login(String username, String password) {
        String user = normalize(username, "用户名不能为空");
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        UserRepository.UserRow row = repository.findByUsername(user)
                .orElseThrow(() -> new HttpErrorException(401, "INVALID_CREDENTIALS", "用户名或密码错误"));
        if (!encoder.matches(password, row.passwordHash())) {
            throw new HttpErrorException(401, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        String token = jwtService.issue(row.id(), row.username(), row.role());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("user", publicUser(row.id(), row.username(), row.role()));
        return body;
    }

    /** GET /api/auth/me — refresh identity from the DB (token may carry stale username). */
    public Map<String, Object> me(AuthUser authUser) {
        UserRepository.UserRow row = repository.findById(authUser.id())
                .orElseThrow(() -> new HttpErrorException(401, "INVALID_TOKEN", "用户不存在"));
        return publicUser(row.id(), row.username(), row.role());
    }

    private static Map<String, Object> publicUser(UUID id, String username, String role) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id.toString());
        user.put("username", username);
        user.put("role", role);
        return user;
    }

    private static String normalize(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
