-- Pending registrations staging table.
-- Users are NOT written to `users` until email is verified.
-- Records expire after 24 hours if never verified.

CREATE TABLE pending_registrations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    username        CITEXT      NOT NULL,
    email           CITEXT      NOT NULL,
    password_hash   TEXT        NOT NULL,
    code_hash       TEXT        NOT NULL,
    code_expires_at TIMESTAMPTZ NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    resend_count    INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pending_email    ON pending_registrations(email);
CREATE INDEX idx_pending_username ON pending_registrations(username);

-- Remove any leftover unverified users from the old flow.
-- Verified (active) users are kept untouched.
DELETE FROM users WHERE email_verified = FALSE;
