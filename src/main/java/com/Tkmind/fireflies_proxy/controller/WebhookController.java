package com.Tkmind.fireflies_proxy.controller;

import com.Tkmind.fireflies_proxy.service.WebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives inbound webhook events from Fireflies.
 *
 * Configure in Fireflies dashboard:
 *   URL:    https://app.tkmind.net/api/webhooks/fireflies
 *   Events: Transcription completed
 *   Secret: set FIREFLIES_WEBHOOK_SECRET env var
 *
 * POST  /api/webhooks/fireflies         — main event receiver
 * GET   /api/webhooks/fireflies/health  — health check (for Fireflies verification ping)
 */
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;

    @PostMapping("/fireflies")
    public ResponseEntity<Map<String, String>> handleFirefliesWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Fireflies-Signature", required = false) String signature) {

        try {
            log.info("Fireflies webhook received ({} bytes)", payload.length());

            // Verify HMAC signature if provided
            if (signature != null && !signature.isBlank()) {
                if (!webhookService.verifyWebhookSignature(payload, signature)) {
                    log.warn("Invalid webhook signature — rejecting");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("status", "error", "message", "Invalid signature"));
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> webhookData = objectMapper.readValue(payload, Map.class);
            webhookService.processWebhook(webhookData);

            return ResponseEntity.ok(Map.of("status", "success"));

        } catch (Exception e) {
            log.error("Error processing Fireflies webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @GetMapping("/fireflies/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "healthy"));
    }
}
