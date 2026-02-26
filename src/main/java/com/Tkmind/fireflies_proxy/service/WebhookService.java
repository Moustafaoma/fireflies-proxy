package com.Tkmind.fireflies_proxy.service;

import com.Tkmind.fireflies_proxy.config.FirefliesConfig;
import com.Tkmind.fireflies_proxy.entity.Meeting;
import com.Tkmind.fireflies_proxy.repository.MeetingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final FirefliesConfig firefliesConfig;
    private final TranscriptService transcriptService;
    private final MeetingRepository meetingRepository;
    private final FirefliesApiService firefliesApiService;

    // ─────────────────────────────────────────────
    // Signature Verification
    // ─────────────────────────────────────────────

    public boolean verifyWebhookSignature(String payload, String signature) {
        String secret = firefliesConfig.getWebhook().getSecret();

        if (secret == null || secret.isBlank()) {
            log.warn("Webhook secret not configured — skipping verification (DEV MODE)");
            return true;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(hash);
            boolean valid = expected.equals(signature);
            if (!valid) log.warn("Webhook signature mismatch");
            return valid;

        } catch (Exception e) {
            log.error("Signature verification error", e);
            return false;
        }
    }

    // ─────────────────────────────────────────────
    // Event Router
    // ─────────────────────────────────────────────

    public void processWebhook(Map<String, Object> payload) {
        String eventType = extractString(payload, "event_type", "event", "eventType");

        log.info("Fireflies webhook event: {}", eventType);

        if (eventType == null) {
            log.warn("Webhook received without event type: {}", payload);
            return;
        }

        switch (eventType) {
            case "Transcription completed", "transcript.completed" ->
                    processTranscriptCompleted(payload);
            case "meeting.started", "Meeting started" ->
                    log.info("Meeting started — no action needed");
            case "meeting.ended", "Meeting ended" ->
                    log.info("Meeting ended — no action needed");
            default ->
                    log.info("Unhandled Fireflies event: {}", eventType);
        }
    }

    // ─────────────────────────────────────────────
    // Transcript Completed Handler
    // ─────────────────────────────────────────────

    /**
     * The webhook payload is only ~80 bytes and contains ONLY the meetingId.
     * Fireflies does NOT send the meeting URL or transcript data in the webhook.
     *
     * Full flow:
     *  1. Extract firefliesMeetingId from webhook
     *  2. Try cheap local DB lookups
     *  3. If no local match → call Fireflies API to get full transcript,
     *     extract the meeting_url from it, then retry local lookup by URL
     *  4. Update local meeting.firefliesMeetingId to the real Fireflies ID
     *  5. Save transcript to DB
     */
    @SuppressWarnings("unchecked")
    private void processTranscriptCompleted(Map<String, Object> payload) {
        try {

            // ── 1. Extract meetingId ──────────────────────────────────────────
            String firefliesMeetingId = extractString(payload,
                    "meetingId", "meeting_id", "MeetingId");

            log.info("Processing transcript for Fireflies meetingId='{}'", firefliesMeetingId);

            if (firefliesMeetingId == null) {
                log.warn("Webhook has no meetingId — cannot process");
                return;
            }

            // ── 2. Cheap local lookup ─────────────────────────────────────────
            Meeting meeting = resolveMeetingLocally(firefliesMeetingId, null);

            // ── 3. No local match → fetch from API, extract URL, retry ────────
            Map<String, Object> transcriptData = null;

            if (meeting == null) {
                log.info("No local meeting found by ID. Fetching full transcript from " +
                        "Fireflies API to extract meeting URL...");

                transcriptData = fetchTranscriptData(firefliesMeetingId);

                if (transcriptData == null) {
                    log.warn("Fireflies transcript not ready yet for meetingId={}. " +
                            "The meeting will be fetchable once the client calls " +
                            "GET /meetings/{id}/transcript.", firefliesMeetingId);
                    return;
                }

                // Log full transcript keys so you can see what Fireflies returns
                log.info("Transcript data keys from Fireflies: {}", transcriptData.keySet());

                // Try matching by meeting URL embedded in the transcript
                String urlFromTranscript = extractString(transcriptData,
                        "meeting_url", "meetingUrl", "meeting_link", "url", "video_url");
                log.info("meeting_url from transcript API response: '{}'", urlFromTranscript);

                if (urlFromTranscript != null) {
                    meeting = resolveMeetingLocally(null, urlFromTranscript);
                }

                // Last resort: match by title
                if (meeting == null) {
                    String title = (String) transcriptData.get("title");
                    log.info("URL match failed. Trying title match: '{}'", title);
                    if (title != null) {
                        meeting = meetingRepository.findAll().stream()
                                .filter(m -> title.equalsIgnoreCase(m.getTitle()))
                                .findFirst()
                                .orElse(null);
                    }
                }

                if (meeting == null) {
                    log.warn("Could not associate Fireflies meetingId='{}' to any local meeting. " +
                                    "Ensure the bot was invited via this proxy. " +
                                    "Transcript title was: '{}'",
                            firefliesMeetingId,
                            transcriptData.get("title"));
                    return;
                }
            }

            // ── 4. Store the real Fireflies meetingId on the local meeting ─────
            if (!firefliesMeetingId.equals(meeting.getFirefliesMeetingId())) {
                log.info("Updating meeting {} firefliesMeetingId: '{}' → '{}'",
                        meeting.getId(), meeting.getFirefliesMeetingId(), firefliesMeetingId);
                meeting.setFirefliesMeetingId(firefliesMeetingId);
                meeting = meetingRepository.save(meeting);
            }

            // ── 5. Fetch transcript data if not already fetched in step 3 ─────
            if (transcriptData == null) {
                transcriptData = fetchTranscriptData(firefliesMeetingId);
                if (transcriptData == null) {
                    log.warn("Transcript still not ready for meetingId={}.", firefliesMeetingId);
                    return;
                }
            }

            // ── 6. Persist transcript ─────────────────────────────────────────
            transcriptService.buildAndSaveFromWebhook(meeting, transcriptData);

            log.info("✅ Transcript saved for local meeting {} (Fireflies meetingId='{}')",
                    meeting.getId(), firefliesMeetingId);

        } catch (Exception e) {
            log.error("Error processing transcript webhook: {}", e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────
    // Fetch Transcript from Fireflies API
    // ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchTranscriptData(String id) {
        try {
            Map<String, Object> apiResponse = firefliesApiService.getTranscript(id);
            if (apiResponse == null || !apiResponse.containsKey("data")) return null;

            Map<String, Object> data = (Map<String, Object>) apiResponse.get("data");
            return (Map<String, Object>) data.get("transcript");

        } catch (Exception e) {
            log.warn("Fireflies API call failed for id='{}': {}", id, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────
    // Local Meeting Resolution
    // ─────────────────────────────────────────────

    /**
     * Strategy 1 — exact firefliesMeetingId column match
     * Strategy 2 — firefliesMeetingId column holds meeting URL (set at invite time)
     * Strategy 3 — meetingUrl column match
     */
    private Meeting resolveMeetingLocally(String firefliesMeetingId, String meetingUrl) {

        if (firefliesMeetingId != null) {
            Optional<Meeting> m = meetingRepository.findByFirefliesMeetingId(firefliesMeetingId);
            if (m.isPresent()) {
                log.debug("Resolved via firefliesMeetingId='{}'", firefliesMeetingId);
                return m.get();
            }
        }

        if (meetingUrl != null && !meetingUrl.isBlank()) {
            // firefliesMeetingId column might hold the URL
            Optional<Meeting> m = meetingRepository.findByFirefliesMeetingId(meetingUrl);
            if (m.isPresent()) {
                log.debug("Resolved via firefliesMeetingId=URL '{}'", meetingUrl);
                return m.get();
            }
            // meetingUrl column
            Optional<Meeting> m2 = meetingRepository.findByMeetingUrl(meetingUrl);
            if (m2.isPresent()) {
                log.debug("Resolved via meetingUrl='{}'", meetingUrl);
                return m2.get();
            }
        }

        return null;
    }

    // ─────────────────────────────────────────────
    // Util
    // ─────────────────────────────────────────────

    private String extractString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }
}