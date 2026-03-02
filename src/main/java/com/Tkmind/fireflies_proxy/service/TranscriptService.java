package com.Tkmind.fireflies_proxy.service;

import com.Tkmind.fireflies_proxy.dto.response.TranscriptResponse;
import com.Tkmind.fireflies_proxy.entity.Meeting;
import com.Tkmind.fireflies_proxy.entity.Transcript;
import com.Tkmind.fireflies_proxy.repository.MeetingRepository;
import com.Tkmind.fireflies_proxy.repository.TranscriptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptService {

    private final TranscriptRepository transcriptRepository;
    private final MeetingRepository    meetingRepository;
    private final FirefliesApiService  firefliesApiService;
    private final LlmSummaryService    llmSummaryService;       // ← NEW
    private final ObjectMapper         objectMapper;

    // ── GET by meeting ID (cached → API fallback) ff ─────────────────────────────

    @Transactional
    public TranscriptResponse getTranscriptByMeetingId(String userEmail, Long meetingId) {

        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new RuntimeException("Meeting not found: " + meetingId));

        if (!meeting.getUser().getEmail().equalsIgnoreCase(userEmail)) {
            throw new SecurityException("Unauthorized");
        }

        return transcriptRepository.findByMeetingId(meetingId)
                .map(existing -> {

                    // If transcript exists but has no AI summary yet — generate it now
                    if (existing.getSummary() == null || existing.getSummary().isBlank()) {
                        log.info("Transcript exists but has no summary — generating AI summary");
                        return fetchAndSaveFromApi(meeting);
                    }

                    return mapToResponse(existing);
                })
                .orElseGet(() -> fetchAndSaveFromApi(meeting));
    }

    // ── Fetch from Fireflies API (manual / fallback) ──────────────────────────

    @Transactional
    @SuppressWarnings("unchecked")
    public TranscriptResponse fetchAndSaveFromApi(Meeting meeting) {

        return transcriptRepository.findByMeetingId(meeting.getId())
                .map(existing -> {

                    // Transcript in DB but no AI summary — regenerate
                    if (existing.getSummary() == null || existing.getSummary().isBlank()) {
                        log.info("Regenerating AI summary for existing transcript, meeting={}",
                                meeting.getId());
                        String aiSummary = llmSummaryService.summarize(
                                existing.getContent(), meeting.getTitle());
                        if (aiSummary != null) {
                            existing.setSummary(aiSummary);
                            transcriptRepository.save(existing);
                            log.info("AI summary saved for meeting {}", meeting.getId());
                        }
                    }

                    return mapToResponse(existing);
                })
                .orElseGet(() -> {

                    String transcriptId = meeting.getFirefliesMeetingId();

                    if (transcriptId == null || transcriptId.isBlank()) {
                        throw new RuntimeException(
                                "No Fireflies transcript ID on meeting " + meeting.getId()
                                        + ". The bot may not have joined yet.");
                    }

                    Map<String, Object> response = firefliesApiService.getTranscript(transcriptId);

                    if (response == null || !response.containsKey("data")) {
                        throw new RuntimeException(
                                "No transcript data returned from Fireflies API");
                    }

                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    Map<String, Object> transcriptData =
                            (Map<String, Object>) data.get("transcript");

                    if (transcriptData == null) {
                        throw new RuntimeException(
                                "Transcript not ready yet for ID: " + transcriptId
                                        + ". Fireflies may still be processing.");
                    }

                    try {
                        return buildAndSaveFromWebhook(meeting, transcriptData);
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Failed to save transcript: " + e.getMessage(), e);
                    }
                });
    }

    // ── Called by WebhookService after fetching full transcript data ──────────

    @Transactional
    @SuppressWarnings("unchecked")
    public TranscriptResponse buildAndSaveFromWebhook(Meeting meeting,
                                                      Map<String, Object> transcriptData)
            throws Exception {

        // Idempotent guard — skip if already saved
        return transcriptRepository.findByMeetingId(meeting.getId())
                .map(existing -> {
                    log.info("Transcript already exists for meeting {} — skipping save",
                            meeting.getId());
                    return mapToResponse(existing);
                })
                .orElseGet(() -> {

                    // ── Build full-text content from sentences ────────────────
                    List<Map<String, Object>> sentences =
                            (List<Map<String, Object>>) transcriptData.get("sentences");

                    StringBuilder content = new StringBuilder();
                    if (sentences != null) {
                        for (Map<String, Object> sentence : sentences) {
                            String speaker   = (String) sentence.get("speaker_name");
                            String text      = (String) sentence.get("text");
                            Object startTime = sentence.get("start_time");
                            if (speaker != null && text != null) {
                                if (startTime != null) {
                                    content.append("[").append(formatTime(startTime)).append("] ");
                                }
                                content.append(speaker).append(": ").append(text).append("\n");
                            }
                        }
                    }

                    // ── Extract Fireflies summary (fallback if LLM fails) ─────
                    String firefliesSummary  = null;
                    String actionItemsText   = null;
                    String keywordsText      = null;
                    String bulletPoints      = null;

                    Object summaryObj = transcriptData.get("summary");
                    if (summaryObj instanceof Map<?, ?> summaryMap) {
                        firefliesSummary = (String) summaryMap.get("overview");

                        Object ai = summaryMap.get("action_items");
                        if (ai instanceof List<?> aiList) {
                            actionItemsText = String.join("\n", aiList.stream()
                                    .map(Object::toString).toList());
                        } else if (ai instanceof String aiStr) {
                            actionItemsText = aiStr;
                        }

                        Object kw = summaryMap.get("keywords");
                        if (kw instanceof List<?> kwList) {
                            keywordsText = String.join(", ", kwList.stream()
                                    .map(Object::toString).toList());
                        } else if (kw instanceof String kwStr) {
                            keywordsText = kwStr;
                        }

                        Object bullet = summaryMap.get("shorthand_bullet");
                        if (bullet instanceof List<?> bList) {
                            bulletPoints = String.join("\n", bList.stream()
                                    .map(Object::toString).toList());
                        } else if (bullet instanceof String bStr) {
                            bulletPoints = bStr;
                        }
                    }

                    // ── Build Fireflies fallback summary ──────────────────────
                    StringBuilder firefliesFallback = new StringBuilder();
                    if (firefliesSummary != null) {
                        firefliesFallback.append("## Overview\n").append(firefliesSummary).append("\n\n");
                    }
                    if (keywordsText != null) {
                        firefliesFallback.append("## Keywords\n").append(keywordsText).append("\n\n");
                    }
                    if (bulletPoints != null) {
                        firefliesFallback.append("## Key Points\n").append(bulletPoints).append("\n");
                    }

                    // ── Speaker labels JSON ───────────────────────────────────
                    String speakerLabelsJson;
                    try {
                        speakerLabelsJson = sentences != null
                                ? objectMapper.writeValueAsString(sentences)
                                : "[]";
                    } catch (Exception e) {
                        speakerLabelsJson = "[]";
                        log.warn("Could not serialize speaker labels: {}", e.getMessage());
                    }

                    // ── Call Gemini AI for summary ────────────────────────────
                    String finalSummary = null;
                    String contentStr   = content.toString();

                    if (!contentStr.isBlank()) {
                        log.info("Calling Gemini AI for transcript summary, meeting={}",
                                meeting.getId());
                        finalSummary = llmSummaryService.summarize(contentStr, meeting.getTitle());
                    }

                    // Fallback: use Fireflies summary if Gemini fails/not configured
                    if (finalSummary == null || finalSummary.isBlank()) {
                        log.info("Using Fireflies summary as fallback for meeting {}",
                                meeting.getId());
                        finalSummary = firefliesFallback.length() > 0
                                ? firefliesFallback.toString()
                                : firefliesSummary;
                    }

                    // ── Persist transcript ────────────────────────────────────
                    Transcript transcript = Transcript.builder()
                            .meeting(meeting)
                            .firefliesTranscriptId((String) transcriptData.get("id"))
                            .content(contentStr)
                            .summary(finalSummary)                  // ← AI summary here
                            .actionItems(actionItemsText)
                            .speakerLabels(speakerLabelsJson)
                            .processedAt(LocalDateTime.now())
                            .build();

                    Transcript saved = transcriptRepository.save(transcript);

                    // Update meeting status to COMPLETED
                    meeting.setStatus(Meeting.MeetingStatus.COMPLETED);
                    meetingRepository.save(meeting);

                    log.info("Transcript saved for meeting {} with {} summary",
                            meeting.getId(),
                            finalSummary != null ? "AI-generated" : "no");

                    return mapToResponse(saved);
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatTime(Object timeObj) {
        try {
            double secs    = Double.parseDouble(timeObj.toString());
            int    minutes = (int) (secs / 60);
            int    seconds = (int) (secs % 60);
            return String.format("%02d:%02d", minutes, seconds);
        } catch (Exception e) {
            return timeObj.toString();
        }
    }

    private TranscriptResponse mapToResponse(Transcript t) {
        return TranscriptResponse.builder()
                .id(t.getId())
                .meetingId(t.getMeeting().getId())
                .content(t.getContent())
                .summary(t.getSummary())
                .actionItems(t.getActionItems())
                .speakerLabels(t.getSpeakerLabels())
                .processedAt(t.getProcessedAt())
                .createdAt(t.getCreatedAt())
                .build();
    }
}