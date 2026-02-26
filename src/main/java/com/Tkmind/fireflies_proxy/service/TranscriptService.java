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
    private final MeetingRepository meetingRepository;
    private final FirefliesApiService firefliesApiService;
    private final ObjectMapper objectMapper;

    // ── GET by meeting ID (cached → API fallback) ─────────────────────────────

    @Transactional
    public TranscriptResponse getTranscriptByMeetingId(String userEmail, Long meetingId) {

        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new RuntimeException("Meeting not found: " + meetingId));

        if (!meeting.getUser().getEmail().equalsIgnoreCase(userEmail)) {
            throw new SecurityException("Unauthorized");
        }

        // Return cached transcript if already saved by webhook or prior fetch
        return transcriptRepository.findByMeetingId(meetingId)
                .map(existing -> {


                    if (existing.getSummary() == null
                            || existing.getSummary().isBlank()) {

                        log.info("Summary missing — refreshing from Fireflies");

                        log.info("Summary missing — skip API refresh");
                        return mapToResponse(existing);                    }

                    return mapToResponse(existing);
                })
                .orElseGet(() -> {
                    log.info("Transcript not cached yet — waiting for webhook");
                    return null;
                });
    }

    // ── Fetch from Fireflies API (manual / fallback) ──────────────────────────

    @Transactional
    @SuppressWarnings("unchecked")
    public TranscriptResponse fetchAndSaveFromApi(Meeting meeting) {

        // Idempotent — return cached if already exists
        return transcriptRepository.findByMeetingId(meeting.getId())
                .map(this::mapToResponse)
                .orElseGet(() -> {

                    String transcriptId = meeting.getFirefliesMeetingId();

                    if (transcriptId == null || transcriptId.isBlank()) {
                        throw new RuntimeException(
                                "No Fireflies transcript ID on meeting " + meeting.getId()
                                        + ". The bot may not have joined yet.");
                    }

                    Map<String, Object> response = firefliesApiService.getTranscript(transcriptId);

                    if (response == null || !response.containsKey("data")) {
                        throw new RuntimeException("No transcript data returned from Fireflies API");
                    }

                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    Map<String, Object> transcriptData = (Map<String, Object>) data.get("transcript");

                    if (transcriptData == null) {
                        log.info("Transcript not ready yet from Fireflies");
                        return null;
                    }

                    try {
                        return buildAndSaveFromWebhook(meeting, transcriptData);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to save transcript: " + e.getMessage(), e);
                    }
                });
    }

    // ── Called by WebhookService after fetching full transcript data ──────────

    /**
     * Builds a Transcript entity from Fireflies API transcript data map and saves it.
     * Idempotent — skips if transcript already exists for this meeting.
     *
     * @param meeting        the local meeting entity
     * @param transcriptData the "transcript" node from Fireflies GraphQL response
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public TranscriptResponse buildAndSaveFromWebhook(Meeting meeting,
                                                      Map<String, Object> transcriptData)
            throws Exception {

        // Idempotent guard
        return transcriptRepository.findByMeetingId(meeting.getId())
                .map(existing -> {
                    log.info("Transcript already exists for meeting {} — skipping save", meeting.getId());
                    return mapToResponse(existing);
                })
                .orElseGet(() -> {

                    // ── Build full-text content from sentences ────────────────────
                    List<Map<String, Object>> sentences =
                            (List<Map<String, Object>>) transcriptData.get("sentences");

                    StringBuilder content = new StringBuilder();
                    if (sentences != null) {
                        for (Map<String, Object> sentence : sentences) {
                            String speaker = (String) sentence.get("speaker_name");
                            String text = (String) sentence.get("text");
                            Object startTime = sentence.get("start_time");
                            if (speaker != null && text != null) {
                                if (startTime != null) {
                                    content.append("[").append(formatTime(startTime)).append("] ");
                                }
                                content.append(speaker).append(": ").append(text).append("\n");
                            }
                        }
                    }

                    // ── Extract summary fields ────────────────────────────────────
                    String summaryText = null;
                    String actionItemsText = null;
                    String keywordsText = null;
                    String bulletPoints = null;

                    Object summaryObj = transcriptData.get("summary");
                    if (summaryObj instanceof Map<?, ?> summaryMap) {
                        summaryText = (String) summaryMap.get("overview");

                        // action_items: String or List
                        Object ai = summaryMap.get("action_items");
                        if (ai instanceof List<?> aiList) {
                            actionItemsText = String.join("\n", aiList.stream()
                                    .map(Object::toString).toList());
                        } else if (ai instanceof String aiStr) {
                            actionItemsText = aiStr;
                        }

                        // keywords
                        Object kw = summaryMap.get("keywords");
                        if (kw instanceof List<?> kwList) {
                            keywordsText = String.join(", ", kwList.stream()
                                    .map(Object::toString).toList());
                        } else if (kw instanceof String kwStr) {
                            keywordsText = kwStr;
                        }

                        // shorthand_bullet
                        Object bullet = summaryMap.get("shorthand_bullet");
                        if (bullet instanceof List<?> bList) {
                            bulletPoints = String.join("\n", bList.stream()
                                    .map(Object::toString).toList());
                        } else if (bullet instanceof String bStr) {
                            bulletPoints = bStr;
                        }
                    }

                    // ── Build enriched summary ────────────────────────────────────
                    StringBuilder enrichedSummary = new StringBuilder();
                    if (summaryText != null) {
                        enrichedSummary.append("## Overview\n").append(summaryText).append("\n\n");
                    }
                    if (keywordsText != null) {
                        enrichedSummary.append("## Keywords\n").append(keywordsText).append("\n\n");
                    }
                    if (bulletPoints != null) {
                        enrichedSummary.append("## Key Points\n").append(bulletPoints).append("\n");
                    }

                    // ── Speaker labels JSON ───────────────────────────────────────
                    String speakerLabelsJson;
                    try {
                        speakerLabelsJson = sentences != null
                                ? objectMapper.writeValueAsString(sentences)
                                : "[]";
                    } catch (Exception e) {
                        speakerLabelsJson = "[]";
                        log.warn("Could not serialize speaker labels: {}", e.getMessage());
                    }

                    // ── Persist ───────────────────────────────────────────────────
                    Transcript transcript = Transcript.builder()
                            .meeting(meeting)
                            .firefliesTranscriptId((String) transcriptData.get("id"))
                            .content(content.toString())
                            .summary(enrichedSummary.length() > 0
                                    ? enrichedSummary.toString()
                                    : summaryText)
                            .actionItems(actionItemsText)
                            .speakerLabels(speakerLabelsJson)
                            .processedAt(LocalDateTime.now())
                            .build();

                    Transcript saved = transcriptRepository.save(transcript);

                    // Update meeting status to COMPLETED
                    meeting.setStatus(Meeting.MeetingStatus.COMPLETED);
                    meetingRepository.save(meeting);

                    log.info("Transcript saved for meeting {} (Fireflies transcript ID: {})",
                            meeting.getId(), transcriptData.get("id"));

                    return mapToResponse(saved);
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatTime(Object timeObj) {
        try {
            double secs = Double.parseDouble(timeObj.toString());
            int minutes = (int) (secs / 60);
            int seconds = (int) (secs % 60);
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