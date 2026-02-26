package com.Tkmind.fireflies_proxy.dto.response;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingResponse {
    private Long id;
    private String title;
    private String participants;
    private LocalDateTime scheduledDate;
    private String meetingUrl;
    private String firefliesMeetingId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
