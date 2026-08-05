package com.nova.studio.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Auth API (T2.2):
 * <ul>
 *   <li>{@code POST /api/auth/register} → 201 {@code {id, username, role}}</li>
 *   <li>{@code POST /api/auth/login} → {@code {token, user}}</li>
 *   <li>{@code GET /api/auth/me} → current user (requires auth)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody JsonNode body) {
        Map<String, Object> user = userService.register(text(body, "username"), text(body, "password"));
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody JsonNode body) {
        return userService.login(text(body, "username"), text(body, "password"));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@org.springframework.web.bind.annotation.RequestAttribute("authUser") AuthUser authUser) {
        return userService.me(authUser);
    }

    private static String text(JsonNode body, String key) {
        if (body == null || !body.has(key) || !body.get(key).isTextual()) {
            return null;
        }
        return body.get(key).asText();
    }
}
