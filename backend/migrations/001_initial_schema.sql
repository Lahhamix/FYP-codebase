-- ============================================================
-- SoleMate Full Database Schema
-- Run via: node backend/migrations/reset.js
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Core Users ───────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    user_id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username       VARCHAR(30)  NOT NULL UNIQUE,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255),
    auth_provider  VARCHAR(20)  NOT NULL DEFAULT 'local',
    email_verified BOOLEAN      NOT NULL DEFAULT FALSE,
    account_status VARCHAR(30)  NOT NULL DEFAULT 'active',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ,
    last_login_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS user_profiles (
    user_id             UUID        PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    display_name        VARCHAR(100),
    profile_picture_url TEXT,
    date_of_birth       DATE,
    gender              VARCHAR(20),
    updated_at          TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS user_settings (
    user_id                UUID    PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    language               VARCHAR(10)  NOT NULL DEFAULT 'en',
    text_size              VARCHAR(10)  NOT NULL DEFAULT 'medium',
    notifications_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    app_lock_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    voice_hints_enabled    BOOLEAN      NOT NULL DEFAULT FALSE,
    auto_share_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    theme                  VARCHAR(10)  NOT NULL DEFAULT 'light',
    updated_at             TIMESTAMPTZ
);

-- ── Auth ─────────────────────────────────────────────────────

-- Holds registrations that haven't been email-verified yet.
-- On success the row is deleted and a users row is created.
CREATE TABLE IF NOT EXISTS pending_registrations (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(30)  NOT NULL,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    code_hash       VARCHAR(255) NOT NULL,
    code_expires_at TIMESTAMPTZ  NOT NULL,
    resend_count    INTEGER      NOT NULL DEFAULT 0,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pending_reg_email    ON pending_registrations(email);
CREATE INDEX IF NOT EXISTS idx_pending_reg_username ON pending_registrations(username);

CREATE TABLE IF NOT EXISTS google_auth (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    google_id    VARCHAR(255) NOT NULL UNIQUE,
    google_email VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens(user_id);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    used       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── Devices ──────────────────────────────────────────────────

-- BLE insole devices registered to a user account.
CREATE TABLE IF NOT EXISTS devices (
    device_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    ble_name          VARCHAR(100) NOT NULL,
    ble_mac           VARCHAR(50),
    firmware_version  VARCHAR(50),
    device_status     VARCHAR(20)  NOT NULL DEFAULT 'active',
    last_connected_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

-- ── Health Readings ───────────────────────────────────────────

-- Vitals captured from the BLE device (HR, SpO2, BP, swelling, steps).
CREATE TABLE IF NOT EXISTS health_readings (
    reading_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    device_id      UUID         REFERENCES devices(device_id) ON DELETE SET NULL,
    heart_rate     NUMERIC(5,1),
    spo2           NUMERIC(5,1),
    bp_systolic    NUMERIC(5,1),
    bp_diastolic   NUMERIC(5,1),
    -- Swelling can be a numeric score ("0.5") or an edema label from firmware ("none", "mild", ...).
    swelling_value TEXT,
    step_count     INTEGER,
    motion_status  VARCHAR(50),
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_health_readings_user_time
    ON health_readings(user_id, recorded_at DESC);

-- Raw plantar pressure matrix from the insole sensors.
CREATE TABLE IF NOT EXISTS pressure_matrix_readings (
    reading_id     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    device_id      UUID        REFERENCES devices(device_id) ON DELETE SET NULL,
    matrix_values  JSONB       NOT NULL,
    pressure_zones JSONB,
    foot_side      VARCHAR(10),
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pressure_matrix_user_time
    ON pressure_matrix_readings(user_id, recorded_at DESC);

-- Derived gait metrics computed from pressure + motion data.
CREATE TABLE IF NOT EXISTS gait_analytics (
    analytics_id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    device_id           UUID        REFERENCES devices(device_id) ON DELETE SET NULL,
    deviation_score     NUMERIC(8,3),
    big_toe_pressure    JSONB,
    plantar_pressure    JSONB,
    ankle_cuff_metrics  JSONB,
    step_symmetry       NUMERIC(5,2),
    risk_flag           BOOLEAN     NOT NULL DEFAULT FALSE,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_gait_analytics_user_time
    ON gait_analytics(user_id, recorded_at DESC);

-- ── Alerts & Sharing ─────────────────────────────────────────

-- Health alerts triggered when readings exceed thresholds.
CREATE TABLE IF NOT EXISTS alerts (
    alert_id   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    alert_type VARCHAR(50) NOT NULL,
    severity   VARCHAR(20) NOT NULL DEFAULT 'info',
    message    TEXT,
    reading_id UUID        REFERENCES health_readings(reading_id) ON DELETE SET NULL,
    sent       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_alerts_user ON alerts(user_id, created_at DESC);

-- Contacts that receive automatic health data or alert emails.
CREATE TABLE IF NOT EXISTS auto_share_recipients (
    recipient_id    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    recipient_name  VARCHAR(100) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    alerts_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_auto_share_user ON auto_share_recipients(user_id);

-- In-app feedback submitted by users.
CREATE TABLE IF NOT EXISTS feedback (
    feedback_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        REFERENCES users(user_id) ON DELETE SET NULL,
    message     TEXT        NOT NULL,
    category    VARCHAR(50),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
