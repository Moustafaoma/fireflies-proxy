package com.Tkmind.fireflies_proxy.repository;
import com.Tkmind.fireflies_proxy.entity.Transcript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
@Repository

public interface TranscriptRepository extends JpaRepository<Transcript, Long> {
    Optional<Transcript> findByMeetingId(Long meetingId);
    Optional<Transcript> findByFirefliesTranscriptId(String firefliesTranscriptId);
}
