package com.Tkmind.fireflies_proxy.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
@Data

public class MeetingLaunchRequest {
    @NotNull(message = "Meeting ID is required")
    private Long meetingId;

    private String meetingUrl;
}
