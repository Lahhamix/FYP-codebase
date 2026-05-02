-- ============================================================
-- SoleMate Database Schema
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "citext";

-- ── ENUM TYPES ───────────────────────────────────────────────
CREATE TYPE account_status  AS ENUM ('pending_verification', 'active', 'disabled', 'deleted');
CREATE TYPE auth_provider   AS ENUM ('local', 'google');
CREATE TYPE alert_severity  AS ENUM ('info', 'warning', 'critical');
CREATE TYPE feedback_status AS ENUM ('open', 'in_review', 'resolved', 'closed');
CREATE TYPE device_status   AS ENUM ('active', 'inactive', 'removed');

-- ── A. USERS ─────────────────────────────────────────────────
CREATE TABLE users (
    user_id        UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    username       CITEXT         NOT NULL UNIQUE,
    email          CITEXT         NOT NULL UNIQUE,
    password_hash  TEXT,
    auth_provider  auth_provider  NOT NULL DEFAULT 'local',
    email_verified BOOLEAN        NOT NULL DEFAULT FALSE,
    account_status account_status NOT NULL DEFAULT 'pending_verification',
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    last_login_at  TIMESTAMPTZ
);

-- ── B. USER PROFILES ─────────────────────────────────────────
CREATE TABLE user_profiles (
    profile_id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID        NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    display_name            TEXT,
    date_of_birth           DATE,
    gender                  TEXT,
    profile_picture_url     TEXT,
    emergency_contact_name  TEXT,
    emergency_contact_phone TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── C. USER SETTINGS ─────────────────────────────────────────
CREATE TABLE user_settings (
    settings_id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID        NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    language              TEXT        NOT NULL DEFAULT 'en',
    text_size             TEXT        NOT NULL DEFAULT 'medium',
    notifications_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    app_lock_enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    voice_hints_enabled   BOOLEAN     NOT NULL DEFAULT FALSE,
    auto_share_enabled    BOOLEAN     NOT NULL DEFAULT FALSE,
    theme                 TEXT        NOT NULL DEFAULT 'light',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── D. EMAIL VERIFICATION CODES ──────────────────────────────
CREATE TABLE email_verification_codes (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        REFERENCES users(user_id) ON DELETE CASCADE,
    email        CITEXT      NOT NULL,
    code_hash    TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    attempts     INT         NOT NULL DEFAULT 0,
    resend_count INT         NOT NULL DEFAULT 0,
    verified     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── E. PASSWORD RESET TOKENS ─────────────────────────────────
CREATE TABLE password_reset_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token_hash TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── F. GOOGLE AUTH ────────────────────────────────────────────
CREATE TABLE google_auth (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    google_id    TEXT        NOT NULL UNIQUE,
    google_email CITEXT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── G. WEARABLE DEVICES ──────────────────────────────────────
CREATE TABLE devices (
    device_id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID          NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    ble_name          TEXT          NOT NULL,
    ble_mac           TEXT,
    firmware_version  TEXT,
    device_status     device_status NOT NULL DEFAULT 'active',
    last_connected_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ── H. HEALTH READINGS ───────────────────────────────────────
CREATE TABLE health_readings (
    reading_id   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    device_id    UUID        REFERENCES devices(device_id) ON DELETE SET NULL,
    heart_rate   SMALLINT,
    spo2         SMALLINT,
    bp_systolic  SMALLINT,
    bp_diastolic SMALLINT,
    swelling_value NUMERIC(6,3),
    step_count   INT,
    motion_status TEXT,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── I. PRESSURE MATRIX READINGS ──────────────────────────────
CREATE TABLE pressure_matrix_readings (
    matrix_id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    device_id      UUID        REFERENCES devices(device_id) ON DELETE SET NULL,
    matrix_values  JSONB       NOT NULL,
    pressure_zones JSONB,
    foot_side      TEXT,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── J. GAIT ANALYTICS ────────────────────────────────────────
CREATE TABLE gait_analytics (
    gait_id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    device_id            UUID        REFERENCES devices(device_id) ON DELETE SET NULL,
    deviation_score      NUMERIC(5,2),
    big_toe_pressure     JSONB,
    plantar_pressure     JSONB,
    ankle_cuff_metrics   JSONB,
    step_symmetry        NUMERIC(5,2),
    risk_flag            BOOLEAN     NOT NULL DEFAULT FALSE,
    recorded_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── K. ALERTS ────────────────────────────────────────────────
CREATE TABLE alerts (
    alert_id   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID           NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    alert_type TEXT           NOT NULL,
    severity   alert_severity NOT NULL DEFAULT 'info',
    message    TEXT           NOT NULL,
    reading_id UUID           REFERENCES health_readings(reading_id) ON DELETE SET NULL,
    sent       BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- ── L. AUTO-SHARE RECIPIENTS ─────────────────────────────────
CREATE TABLE auto_share_recipients (
    recipient_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    recipient_name  TEXT        NOT NULL,
    recipient_email CITEXT      NOT NULL,
    verified        BOOLEAN     NOT NULL DEFAULT FALSE,
    alerts_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, recipient_email)
);

-- ── M. FEEDBACK ──────────────────────────────────────────────
CREATE TABLE feedback (
    feedback_id UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID            REFERENCES users(user_id) ON DELETE SET NULL,
    message     TEXT            NOT NULL,
    category    TEXT,
    status      feedback_status NOT NULL DEFAULT 'open',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ── N. REFRESH TOKENS ────────────────────────────────────────
CREATE TABLE refresh_tokens (
    token_id   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token_hash TEXT        NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── INDEXES ──────────────────────────────────────────────────
CREATE INDEX idx_users_email          ON users(email);
CREATE INDEX idx_users_username       ON users(username);
CREATE INDEX idx_users_status         ON users(account_status);

CREATE INDEX idx_verif_user           ON email_verification_codes(user_id);
CREATE INDEX idx_verif_email          ON email_verification_codes(email);

CREATE INDEX idx_devices_user         ON devices(user_id);

CREATE INDEX idx_readings_user_time   ON health_readings(user_id, recorded_at DESC);
CREATE INDEX idx_readings_device_time ON health_readings(device_id, recorded_at DESC);

CREATE INDEX idx_pressure_user_time   ON pressure_matrix_readings(user_id, recorded_at DESC);

CREATE INDEX idx_gait_user_time       ON gait_analytics(user_id, recorded_at DESC);

CREATE INDEX idx_alerts_user          ON alerts(user_id, created_at DESC);
CREATE INDEX idx_alerts_severity      ON alerts(user_id, severity);

CREATE INDEX idx_recipients_user      ON auto_share_recipients(user_id);

CREATE INDEX idx_refresh_user         ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_hash         ON refresh_tokens(token_hash);

-- ── AUTO-UPDATE updated_at TRIGGER ───────────────────────────
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
  BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_profiles_updated_at
  BEFORE UPDATE ON user_profiles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_settings_updated_at
  BEFORE UPDATE ON user_settings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_devices_updated_at
  BEFORE UPDATE ON devices FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_recipients_updated_at
  BEFORE UPDATE ON auto_share_recipients FOR EACH ROW EXECUTE FUNCTION set_updated_at();
