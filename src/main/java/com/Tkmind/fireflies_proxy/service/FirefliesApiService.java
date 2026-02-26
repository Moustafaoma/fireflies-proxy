package com.Tkmind.fireflies_proxy.service;

import com.Tkmind.fireflies_proxy.config.FirefliesConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class FirefliesApiService {

    private final RestTemplate restTemplate;
    private final FirefliesConfig firefliesConfig;

    // ─────────────────────────────────────────────
    // Cache — getMe (5 min TTL + 429 backoff)
    // ─────────────────────────────────────────────
    private Map<String, Object> cachedMe      = null;
    private long meCacheExpiry                = 0L;
    private long meBackoffUntil               = 0L;
    private static final long ME_CACHE_TTL_MS   = 5 * 60_000L;
    private static final long ME_BACKOFF_TTL_MS = 5 * 60_000L;

    // ─────────────────────────────────────────────
    // Cache — getTranscript (5 min TTL)
    // ─────────────────────────────────────────────
    private final Map<String, Map<String, Object>> transcriptCache       = new ConcurrentHashMap<>();
    private final Map<String, Long>                transcriptCacheExpiry = new ConcurrentHashMap<>();
    private static final long TRANSCRIPT_CACHE_TTL_MS = 5 * 60_000L;

    // ─────────────────────────────────────────────
    // Invite Fireflies Bot
    // ─────────────────────────────────────────────

    public Map<String, Object> addBotToMeeting(String meetingUrl, String title) {
        String mutation = """
                mutation AddToLiveMeeting($meeting_link: String!) {
                  addToLiveMeeting(meeting_link: $meeting_link) {
                    success
                    message
                  }
                }
                """;
        Map<String, Object> variables = new HashMap<>();
        variables.put("meeting_link", meetingUrl);
        return executeGraphQL(mutation, variables);
    }

    // ─────────────────────────────────────────────
    // Get Transcript (with 5-min cache)
    // ─────────────────────────────────────────────

    public Map<String, Object> getTranscript(String transcriptId) {
        long now = System.currentTimeMillis();

        if (transcriptCache.containsKey(transcriptId)
                && now < transcriptCacheExpiry.getOrDefault(transcriptId, 0L)) {
            log.debug("Cache HIT — transcript id={}", transcriptId);
            return transcriptCache.get(transcriptId);
        }

        log.debug("Cache MISS — fetching transcript id={} from Fireflies", transcriptId);

        String query = """
                query Transcript($id: String!) {
                  transcript(id: $id) {
                    id
                    title
                    date
                    duration
                    meeting_link
                    summary {
                      overview
                      action_items
                      keywords
                      shorthand_bullet
                    }
                    sentences {
                      text
                      speaker_name
                      start_time
                      end_time
                    }
                  }
                }
                """;
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", transcriptId);

        Map<String, Object> result = executeGraphQL(query, variables);

        if (result != null && !result.containsKey("errors")) {
            transcriptCache.put(transcriptId, result);
            transcriptCacheExpiry.put(transcriptId, now + TRANSCRIPT_CACHE_TTL_MS);
            log.debug("Cached transcript id={} for {} min", transcriptId, TRANSCRIPT_CACHE_TTL_MS / 60_000);
        }

        return result;
    }

    // ─────────────────────────────────────────────
    // List Transcripts
    // ─────────────────────────────────────────────

    public Map<String, Object> listTranscripts(int limit, int skip) {
        String query = """
                query Transcripts($limit: Int, $skip: Int) {
                  transcripts(limit: $limit, skip: $skip) {
                    id
                    title
                    date
                    duration
                    meeting_link
                    organizer_email
                    participants
                  }
                }
                """;
        Map<String, Object> variables = new HashMap<>();
        variables.put("limit", limit);
        variables.put("skip", skip);
        return executeGraphQL(query, variables);
    }

    // ─────────────────────────────────────────────
    // Verify API Key (5-min cache + 429 backoff)
    // ─────────────────────────────────────────────

    public Map<String, Object> getMe() {
        long now = System.currentTimeMillis();

        // Still in 429 backoff — return stale cache or throw friendly error
        if (now < meBackoffUntil) {
            long waitSec = (meBackoffUntil - now) / 1000;
            log.warn("getMe() blocked — 429 backoff active for {}s more", waitSec);
            if (cachedMe != null) {
                log.debug("Returning stale cached user during 429 backoff");
                return cachedMe;
            }
            throw new RuntimeException(
                    "Fireflies API rate limit active. Please wait " + waitSec + " seconds.");
        }

        // Fresh cache hit
        if (cachedMe != null && now < meCacheExpiry) {
            log.debug("Cache HIT — Fireflies user (expires in {}s)", (meCacheExpiry - now) / 1000);
            return cachedMe;
        }

        log.debug("Cache MISS — fetching Fireflies user");

        String query = """
                query {
                  user {
                    user_id
                    email
                    name
                    minutes_consumed
                    is_admin
                  }
                }
                """;

        Map<String, Object> result = executeGraphQL(query, null);

        if (result != null && result.containsKey("errors")) {
            extractRetryAfterAndSetBackoff(result, now);
            return result;
        }

        if (result != null) {
            cachedMe       = result;
            meCacheExpiry  = now + ME_CACHE_TTL_MS;
            meBackoffUntil = 0L;
            log.debug("Cached Fireflies user for {} min", ME_CACHE_TTL_MS / 60_000);
        }

        return result;
    }

    // ─────────────────────────────────────────────
    // Extract retryAfter from 429 and set backoff
    // ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void extractRetryAfterAndSetBackoff(Map<String, Object> result, long now) {
        try {
            var errors = (java.util.List<Map<String, Object>>) result.get("errors");
            if (errors != null && !errors.isEmpty()) {
                var first = errors.get(0);
                String code = (String) first.get("code");
                if ("too_many_requests".equals(code)) {
                    var ext  = (Map<String, Object>) first.get("extensions");
                    if (ext != null) {
                        var meta = (Map<String, Object>) ext.get("metadata");
                        if (meta != null && meta.get("retryAfter") instanceof Number retryAfter) {
                            meBackoffUntil = retryAfter.longValue();
                            log.warn("429 — backoff set until {} ({}s away)",
                                    new java.util.Date(meBackoffUntil),
                                    (meBackoffUntil - now) / 1000);
                            return;
                        }
                    }
                    meBackoffUntil = now + ME_BACKOFF_TTL_MS;
                    log.warn("429 — fallback backoff {}s", ME_BACKOFF_TTL_MS / 1000);
                }
            }
        } catch (Exception e) {
            meBackoffUntil = now + ME_BACKOFF_TTL_MS;
            log.warn("Could not parse retryAfter from 429: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Core GraphQL Executor
    // ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeGraphQL(String query, Map<String, Object> variables) {
        String apiKey = firefliesConfig.getApi().getApiKey();

        if (apiKey == null || apiKey.isBlank() || apiKey.equals("your-api-key-here")) {
            throw new RuntimeException("Fireflies API key not configured. Set FIREFLIES_API_KEY.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);
        if (variables != null && !variables.isEmpty()) {
            requestBody.put("variables", variables);
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    firefliesConfig.getApi().getBaseUrl(),
                    HttpMethod.POST,
                    request,
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            log.debug("Fireflies API response: {}", body);

            if (body != null && body.containsKey("errors")) {
                log.error("Fireflies GraphQL errors: {}", body.get("errors"));
            }

            return body;

        } catch (HttpClientErrorException e) {
            log.error("Fireflies HTTP error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Fireflies API error " + e.getStatusCode()
                    + ": " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling Fireflies API", e);
            throw new RuntimeException("Failed to call Fireflies API: " + e.getMessage());
        }
    }
}