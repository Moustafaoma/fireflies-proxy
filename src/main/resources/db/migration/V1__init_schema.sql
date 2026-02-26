-- ============================================================
-- V1__init_schema.sql  –  Fireflies Proxy initial schema
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
                                     id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                                     email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    );

CREATE TABLE IF NOT EXISTS meetings (
                                        id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        user_id              BIGINT NOT NULL,
                                        title                VARCHAR(500) NOT NULL,
    participants         TEXT,
    scheduled_date       TIMESTAMP NOT NULL,
    meeting_url          VARCHAR(1000),
    fireflies_meeting_id VARCHAR(255),
    status               VARCHAR(50) DEFAULT 'SCHEDULED',
    created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_meetings_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS transcripts (
                                           id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
                                           meeting_id               BIGINT NOT NULL,
                                           fireflies_transcript_id  VARCHAR(255),
    content                  LONGTEXT,
    summary                  TEXT,
    action_items             TEXT,
    speaker_labels           JSON,
    processed_at             TIMESTAMP NULL,
    created_at               TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_transcripts_meeting
    FOREIGN KEY (meeting_id) REFERENCES meetings (id) ON DELETE CASCADE
    );

-- ── Indexes ──────────────────────────────────────────────────────────────────

CREATE INDEX idx_users_email
    ON users (email);

CREATE INDEX idx_meetings_user_id
    ON meetings (user_id);

CREATE UNIQUE INDEX ux_meetings_fireflies_meeting_id
    ON meetings (fireflies_meeting_id);

CREATE UNIQUE INDEX ux_transcripts_meeting_id
    ON transcripts (meeting_id);

CREATE UNIQUE INDEX ux_transcripts_fireflies_transcript_id
    ON transcripts (fireflies_transcript_id);
