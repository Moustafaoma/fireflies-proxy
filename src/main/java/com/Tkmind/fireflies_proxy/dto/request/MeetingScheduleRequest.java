package com.Tkmind.fireflies_proxy.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MeetingScheduleRequest {

    @NotBlank(message = "Title is required")
    private String title;

    @NotNull(message = "Scheduled date is required")
    private LocalDateTime scheduledDate;

    private List<String> participants;

    /**
     * Zoom / Google Meet / Teams meeting URL.
     * If provided at schedule time, the Fireflies bot will be invited immediately.
     * Can also be provided later via the /launch endpoint.
     */
    private String meetingUrl;

    /**
     * If true (default), automatically invite the Fireflies bot
     * when a meetingUrl is present. Set to false to defer bot invite.
     */
    private boolean inviteBot = true;
}
