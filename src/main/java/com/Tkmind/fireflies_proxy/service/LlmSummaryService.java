package com.Tkmind.fireflies_proxy.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Calls Groq API (free tier) to generate AI summary from meeting transcript.
 *
 * Get your FREE API key at: https://console.groq.com
 * Free tier: 14,400 requests/day, 30 requests/min — no credit card needed.
 *
 * Model used: llama-3.3-70b-versatile (free, fast, high quality)
 */
@Service
@Slf4j
public class LlmSummaryService {

    private static final String GROQ_URL   = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";

    @Value("${groq.api.key:}")
    private String groqApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    // ─────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────

    public String summarize(String transcriptContent, String meetingTitle) {

        if (groqApiKey == null || groqApiKey.isBlank()) {
            log.warn("Groq API key not configured — skipping AI summary. Set GROQ_API_KEY.");
            return null;
        }

        if (transcriptContent == null || transcriptContent.isBlank()) {
            log.warn("Transcript content is empty — skipping AI summary.");
            return null;
        }

        String content = truncate(transcriptContent, 30_000);
        String prompt  = buildPrompt(meetingTitle, content);

        try {
            log.info("Calling Groq AI to summarize transcript for meeting: {}", meetingTitle);
            String result = callGroq(prompt);
            log.info("Groq AI summary generated successfully ({} chars)",
                    result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("Groq AI summarization failed: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────
    // Prompt
    // ─────────────────────────────────────────────

    private String buildPrompt(String meetingTitle, String transcript) {
        return """
                You are an expert meeting assistant. Analyze the following meeting transcript and provide a structured summary.

                Meeting Title: %s

                Transcript:
                %s

                Please provide a summary in the following exact markdown format:

                ## Overview
                [2-3 sentence summary of what the meeting was about and key outcomes]

                ## Key Discussion Points
                [bullet points of the main topics discussed]

                ## Decisions Made
                [bullet points of any decisions or conclusions reached, write "None identified" if none]

                ## Action Items
                [bullet points of action items with owner if mentioned, write "None identified" if none]

                ## Keywords
                [comma-separated list of 5-10 relevant keywords from the meeting]

                Keep the summary concise, professional, and focused on what matters most.
                """.formatted(meetingTitle != null ? meetingTitle : "Meeting", transcript);
    }

    // ─────────────────────────────────────────────
    // Call Groq REST API (OpenAI-compatible)
    // ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String callGroq(String prompt) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(groqApiKey);

        Map<String, Object> message = Map.of(
                "role",    "user",
                "content", prompt
        );

        Map<String, Object> body = Map.of(
                "model",       GROQ_MODEL,
                "messages",    List.of(message),
                "max_tokens",  1024,
                "temperature", 0.3
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                GROQ_URL,
                HttpMethod.POST,
                request,
                Map.class
        );

        Map<String, Object> responseBody = response.getBody();

        if (responseBody == null) {
            throw new RuntimeException("Empty response from Groq API");
        }

        // Parse: response.choices[0].message.content
        List<Map<String, Object>> choices =
                (List<Map<String, Object>>) responseBody.get("choices");

        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices in Groq response: " + responseBody);
        }

        Map<String, Object> messageResponse =
                (Map<String, Object>) choices.get(0).get("message");

        String text = (String) messageResponse.get("content");

        if (text == null || text.isBlank()) {
            throw new RuntimeException("Empty content in Groq response");
        }

        return text.trim();
    }

    // ─────────────────────────────────────────────
    // Truncate to avoid token limits
    // ─────────────────────────────────────────────

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        log.warn("Transcript truncated from {} to {} chars for Groq", text.length(), maxChars);
        return text.substring(0, maxChars) + "\n\n[... transcript truncated ...]";
    }
}