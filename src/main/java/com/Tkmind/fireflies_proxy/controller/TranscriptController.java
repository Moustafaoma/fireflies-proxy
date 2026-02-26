package com.Tkmind.fireflies_proxy.controller;

import com.Tkmind.fireflies_proxy.dto.response.TranscriptResponse;
import com.Tkmind.fireflies_proxy.service.TranscriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/meetings")
@RequiredArgsConstructor
@Slf4j
public class TranscriptController {

    private final TranscriptService transcriptService;

    @GetMapping("/{meetingId}/transcript")
    public ResponseEntity<?> getTranscript(
            @RequestHeader("X-User-Email") String userEmail,
            @PathVariable Long meetingId) {

        TranscriptResponse transcript =
                transcriptService.getTranscriptByMeetingId(userEmail, meetingId);


        if (transcript == null) {
            log.info("Transcript not ready yet for meeting {}", meetingId);
            return ResponseEntity.accepted().build(); // 202
        }


        return ResponseEntity.ok(transcript);
    }
}