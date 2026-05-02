-- Ensures pending_registrations table exists.
-- Users are only promoted to the users table after email verification.
DROP TABLE IF EXISTS email_verification_codes;

CREATE TABLE IF NOT EXISTS pending_registrations (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(30)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    code_hash     VARCHAR(255) NOT NULL,
    code_expires_at TIMESTAMPTZ NOT NULL,
    resend_count  INTEGER      NOT NULL DEFAULT 0,
    attempts      INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pending_reg_email    ON pending_registrations(email);
CREATE INDEX IF NOT EXISTS idx_pending_reg_username ON pending_registrations(username);
