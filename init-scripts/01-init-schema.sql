-- ============================================================================
-- Event-driven Notification Platform Schema
-- Database: PostgreSQL 16
-- ============================================================================

-- 1. notification_log
CREATE TABLE IF NOT EXISTS notification_log (
    id              BIGSERIAL PRIMARY KEY,
    notification_id VARCHAR(50)  NOT NULL UNIQUE,
    event_id        VARCHAR(50)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    user_id         BIGINT       NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    recipient       VARCHAR(255),
    error_message   TEXT,
    sent_at         TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    CONSTRAINT chk_notif_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_notif_channel CHECK (channel IN ('EMAIL', 'SLACK', 'PUSH', 'IN_APP'))
);

CREATE INDEX IF NOT EXISTS idx_notif_log_user_id ON notification_log (user_id);
CREATE INDEX IF NOT EXISTS idx_notif_log_event_id ON notification_log (event_id);
CREATE INDEX IF NOT EXISTS idx_notif_log_status ON notification_log (status);
CREATE INDEX IF NOT EXISTS idx_notif_log_channel ON notification_log (channel);
CREATE INDEX IF NOT EXISTS idx_notif_log_created_at ON notification_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notif_log_user_created ON notification_log (user_id, created_at DESC);

-- 2. delivery_stats
CREATE TABLE IF NOT EXISTS delivery_stats (
    id             BIGSERIAL PRIMARY KEY,
    date           DATE        NOT NULL,
    channel        VARCHAR(20) NOT NULL,
    sent_count     INT         DEFAULT 0,
    failed_count   INT         DEFAULT 0,
    success_rate   DECIMAL(5,2),

    CONSTRAINT uq_delivery_stats_date_channel UNIQUE (date, channel),
    CONSTRAINT chk_stats_channel CHECK (channel IN ('EMAIL', 'SLACK', 'PUSH', 'IN_APP'))
);

CREATE INDEX IF NOT EXISTS idx_delivery_stats_date ON delivery_stats (date DESC);

-- 3. notification_settings
CREATE TABLE IF NOT EXISTS notification_settings (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL UNIQUE,
    email_enabled  BOOLEAN      DEFAULT TRUE,
    slack_enabled  BOOLEAN      DEFAULT FALSE,
    push_enabled   BOOLEAN      DEFAULT FALSE,
    in_app_enabled BOOLEAN      DEFAULT TRUE,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_notif_settings_user_id ON notification_settings (user_id);

-- 4. processed_events
CREATE TABLE IF NOT EXISTS processed_events (
    id            BIGSERIAL PRIMARY KEY,
    event_id      VARCHAR(50) NOT NULL UNIQUE,
    consumer_group VARCHAR(50) NOT NULL,
    processed_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_processed_events_event_id ON processed_events (event_id);
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at ON processed_events (processed_at);

-- 5. dlq_messages
CREATE TABLE IF NOT EXISTS dlq_messages (
    id               BIGSERIAL PRIMARY KEY,
    original_topic   VARCHAR(100) NOT NULL,
    event_id         VARCHAR(50),
    payload          TEXT         NOT NULL,
    error_message    TEXT,
    retry_count      INT          DEFAULT 0,
    status           VARCHAR(20)  DEFAULT 'PENDING',
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    retried_at       TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_dlq_status CHECK (status IN ('PENDING', 'RETRIED', 'DISCARDED'))
);

CREATE INDEX IF NOT EXISTS idx_dlq_messages_status ON dlq_messages (status);
CREATE INDEX IF NOT EXISTS idx_dlq_messages_original_topic ON dlq_messages (original_topic);
CREATE INDEX IF NOT EXISTS idx_dlq_messages_created_at ON dlq_messages (created_at DESC);
