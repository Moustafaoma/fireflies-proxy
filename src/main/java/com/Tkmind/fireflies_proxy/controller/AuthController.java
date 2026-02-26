package com.Tkmind.fireflies_proxy.controller;

import com.Tkmind.fireflies_proxy.dto.response.AuthResponse;
import com.Tkmind.fireflies_proxy.entity.User;
import com.Tkmind.fireflies_proxy.repository.UserRepository;
import com.Tkmind.fireflies_proxy.service.FirefliesApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Simple email-based authentication.
 *
 * No JWT — every request carries:   X-User-Email: user@example.com
 *
 * Endpoints:
 *   POST /api/auth/register  { "email": "..." }  — create user (idempotent)
 *   GET  /api/auth/me        X-User-Email header  — get user profile
 *   GET  /api/auth/fireflies X-User-Email header  — verify Fireflies API key
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserRepository userRepository;
    private final FirefliesApiService firefliesApiService;

    // ── Register ──────────────────────────────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody Map<String, String> body) {
        String email = body.get("email");

        if (email == null || email.isBlank() || !email.contains("@")) {
            return ResponseEntity.badRequest().build();
        }

        email = email.trim().toLowerCase();
        final String normalizedEmail = email;

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseGet(() -> {
                    User newUser = User.builder().email(normalizedEmail).build();
                    log.info("Registering new user: {}", normalizedEmail);
                    return userRepository.save(newUser);
                });

        return ResponseEntity.ok(buildResponse(user));
    }

    // ── Me ────────────────────────────────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(
            @RequestHeader("X-User-Email") String email) {

        return userRepository.findByEmail(email.trim().toLowerCase())
                .map(user -> ResponseEntity.ok(buildResponse(user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // ── Verify Fireflies connection ───────────────────────────────────────────

    @GetMapping("/fireflies")
    public ResponseEntity<Map<String, Object>> verifyFireflies(
            @RequestHeader("X-User-Email") String email) {

        userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new RuntimeException(
                        "User not found. Register first via POST /api/auth/register"));

        try {
            Map<String, Object> result = firefliesApiService.getMe();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Fireflies API key verification failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", "Fireflies API key is invalid or not configured",
                            "detail", e.getMessage()
                    ));
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private AuthResponse buildResponse(User user) {
        return AuthResponse.builder()
                .email(user.getEmail())
                .tokenType("ApiKey")
                .message("Send header X-User-Email: " + user.getEmail() + " on all requests")
                .build();
    }
}
