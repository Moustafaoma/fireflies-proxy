package com.Tkmind.fireflies_proxy.repository;

import com.Tkmind.fireflies_proxy.entity.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MeetingRepository extends JpaRepository<Meeting, Long> {
    List<Meeting> findByUserIdOrderByScheduledDateDesc(Long userId);
    Optional<Meeting> findByFirefliesMeetingId(String firefliesMeetingId);

    // ✅ NEW: fallback match when firefliesMeetingId was stored as URL
    Optional<Meeting> findByMeetingUrl(String meetingUrl);
}