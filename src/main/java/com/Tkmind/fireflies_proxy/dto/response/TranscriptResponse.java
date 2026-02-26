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
public class TranscriptResponse {
    private Long id;
    private Long meetingId;
    private String content;
    private String summary;
    private String actionItems;

    private String speakerLabels;
    private LocalDateTime processedAt;
    private LocalDateTime createdAt;
}
