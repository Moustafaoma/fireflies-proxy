package com.Tkmind.fireflies_proxy.service;

import com.Tkmind.fireflies_proxy.dto.request.MeetingLaunchRequest;
import com.Tkmind.fireflies_proxy.dto.request.MeetingScheduleRequest;
import com.Tkmind.fireflies_proxy.dto.response.MeetingResponse;
import com.Tkmind.fireflies_proxy.entity.Meeting;
import com.Tkmind.fireflies_proxy.entity.User;
import com.Tkmind.fireflies_proxy.repository.MeetingRepository;
import com.Tkmind.fireflies_proxy.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final UserRepository userRepository;
    private final FirefliesApiService firefliesApiService;

    // ─────────────────────────────────────────────
    // Schedule Meeting
    // ─────────────────────────────────────────────

    @Transactional
    public MeetingResponse scheduleMeeting(String userEmail, MeetingScheduleRequest request) {

        User user = findUserOrThrow(userEmail);

        Meeting meeting = Meeting.builder()
                .user(user)
                .title(request.getTitle())
                .scheduledDate(request.getScheduledDate())
                .participants(request.getParticipants() != null
                        ? String.join(",", request.getParticipants())
                        : null)
                .meetingUrl(request.getMeetingUrl())
                .status(Meeting.MeetingStatus.SCHEDULED)
                .build();

        meeting = meetingRepository.save(meeting);

        log.info("Meeting {} scheduled by {}", meeting.getId(), userEmail);

        // Auto invite bot if URL exists
        if (request.isInviteBot()
                && request.getMeetingUrl() != null
                && !request.getMeetingUrl().isBlank()) {

            meeting = inviteBotSafely(meeting);
        }

        return mapToResponse(meeting);
    }

    // ─────────────────────────────────────────────
    // Launch Meeting
    // ─────────────────────────────────────────────

    @Transactional
    public MeetingResponse launchMeeting(String userEmail, MeetingLaunchRequest request) {

        Meeting meeting = meetingRepository.findById(request.getMeetingId())
                .orElseThrow(() ->
                        new EntityNotFoundException("Meeting not found: " + request.getMeetingId()));

        if (!meeting.getUser().getEmail().equalsIgnoreCase(userEmail)) {
            throw new SecurityException("Unauthorized: meeting belongs to another user");
        }

        if (meeting.getStatus() == Meeting.MeetingStatus.IN_PROGRESS) {
            log.info("Meeting {} already launched", meeting.getId());
            return mapToResponse(meeting);
        }

        if (request.getMeetingUrl() != null && !request.getMeetingUrl().isBlank()) {
            meeting.setMeetingUrl(request.getMeetingUrl());
        }

        if (meeting.getMeetingUrl() == null || meeting.getMeetingUrl().isBlank()) {
            throw new IllegalArgumentException("No meeting URL available. Provide meetingUrl.");
        }

        meeting.setStatus(Meeting.MeetingStatus.IN_PROGRESS);
        meeting = meetingRepository.save(meeting);

        log.info("Meeting {} launched by {}", meeting.getId(), userEmail);

        meeting = inviteBotSafely(meeting);

        return mapToResponse(meeting);
    }

    // ─────────────────────────────────────────────
    // Read Operations
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MeetingResponse> getUserMeetings(String userEmail) {
        User user = findUserOrThrow(userEmail);
        return meetingRepository
                .findByUserIdOrderByScheduledDateDesc(user.getId())
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public MeetingResponse getMeetingById(String userEmail, Long meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() ->
                        new EntityNotFoundException("Meeting not found: " + meetingId));

        if (!meeting.getUser().getEmail().equalsIgnoreCase(userEmail)) {
            throw new SecurityException("Unauthorized access");
        }

        return mapToResponse(meeting);
    }

    // ─────────────────────────────────────────────
    // Fireflies Bot Invite
    // ─────────────────────────────────────────────

    /**
     * Invite Fireflies bot and store the meeting URL as the lookup key.
     *
     * ⚠️  The addToLiveMeeting mutation returns only {success, message} —
     * Fireflies does NOT return their internal meetingId at invite time.
     * We store the meeting URL as firefliesMeetingId so that:
     *   - the WebhookService can later update it with the real Fireflies meetingId
     *   - GET /transcript can fall back to fetching by URL match
     */
    @SuppressWarnings("unchecked")
    public Meeting inviteBotSafely(Meeting meeting) {

        // Prevent duplicate invite
        if (meeting.getFirefliesMeetingId() != null) {
            log.info("Bot already invited for meeting {}", meeting.getId());
            return meeting;
        }

        try {
            Map<String, Object> response =
                    firefliesApiService.addBotToMeeting(
                            meeting.getMeetingUrl(),
                            meeting.getTitle());

            if (response != null && response.containsKey("data")) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");

                if (data != null && data.containsKey("addToLiveMeeting")) {
                    Map<String, Object> result = (Map<String, Object>) data.get("addToLiveMeeting");
                    Boolean success = result != null ? (Boolean) result.get("success") : null;
                    String message = result != null ? (String) result.get("message") : null;

                    if (Boolean.TRUE.equals(success)) {
                        // ✅ Store meeting URL as the lookup key.
                        // WebhookService will overwrite this with the real Fireflies meetingId
                        // when the webhook arrives.
                        meeting.setFirefliesMeetingId(meeting.getMeetingUrl());
                        meeting = meetingRepository.save(meeting);
                        log.info("Fireflies bot invited to meeting {}: {}", meeting.getId(), message);
                    } else {
                        log.warn("Fireflies bot invite returned success=false for meeting {}: {}",
                                meeting.getId(), message);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Bot invite failed for meeting {}: {}", meeting.getId(), e.getMessage());
        }

        return meeting;
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private User findUserOrThrow(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "User not found: " + email +
                                        ". Please register first via /api/auth/register"));
    }

    private MeetingResponse mapToResponse(Meeting m) {
        return MeetingResponse.builder()
                .id(m.getId())
                .title(m.getTitle())
                .participants(m.getParticipants())
                .scheduledDate(m.getScheduledDate())
                .meetingUrl(m.getMeetingUrl())
                .firefliesMeetingId(m.getFirefliesMeetingId())
                .status(m.getStatus().name())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}