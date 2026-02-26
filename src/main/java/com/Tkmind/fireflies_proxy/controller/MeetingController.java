package com.Tkmind.fireflies_proxy.controller;

import com.Tkmind.fireflies_proxy.dto.request.MeetingLaunchRequest;
import com.Tkmind.fireflies_proxy.dto.request.MeetingScheduleRequest;
import com.Tkmind.fireflies_proxy.dto.response.MeetingResponse;
import com.Tkmind.fireflies_proxy.service.MeetingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Meeting endpoints.
 * All requests require header:  X-User-Email: user@example.com
 *
 * POST   /api/meetings/schedule    — create meeting (+ optional bot invite)
 * POST   /api/meetings/launch      — launch meeting + invite bot
 * GET    /api/meetings             — list user's meetings
 * GET    /api/meetings/{id}        — get single meeting
 */
@RestController
@RequestMapping("/meetings")
@RequiredArgsConstructor
@Slf4j
public class MeetingController {

    private final MeetingService meetingService;

    @PostMapping("/schedule")
    public ResponseEntity<MeetingResponse> scheduleMeeting(
            @RequestHeader("X-User-Email") String userEmail,
            @Valid @RequestBody MeetingScheduleRequest request) {

        MeetingResponse response = meetingService.scheduleMeeting(userEmail, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/launch")
    public ResponseEntity<MeetingResponse> launchMeeting(
            @RequestHeader("X-User-Email") String userEmail,
            @Valid @RequestBody MeetingLaunchRequest request) {

        MeetingResponse response = meetingService.launchMeeting(userEmail, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<MeetingResponse>> getUserMeetings(
            @RequestHeader("X-User-Email") String userEmail) {

        return ResponseEntity.ok(meetingService.getUserMeetings(userEmail));
    }

    @GetMapping("/{meetingId}")
    public ResponseEntity<MeetingResponse> getMeetingById(
            @RequestHeader("X-User-Email") String userEmail,
            @PathVariable Long meetingId) {

        return ResponseEntity.ok(meetingService.getMeetingById(userEmail, meetingId));
    }
}
