const db = require('../config/db');

exports.findByEmailOrUsername = (identifier) =>
  db.query(
    `SELECT user_id, username, email, password_hash, auth_provider,
            email_verified, account_status, last_login_at
     FROM users
     WHERE (email = $1 OR username = $1) LIMIT 1`,
    [identifier]
  );

exports.findById = (userId) =>
  db.query(
    `SELECT user_id, username, email, auth_provider, email_verified,
            account_status, created_at, last_login_at
     FROM users WHERE user_id = $1`,
    [userId]
  );

exports.findByEmail = (email) =>
  db.query('SELECT user_id FROM users WHERE email = $1 LIMIT 1', [email]);

exports.findByUsername = (username) =>
  db.query('SELECT user_id FROM users WHERE username = $1 LIMIT 1', [username]);

exports.create = (username, email, passwordHash, provider) =>
  db.query(
    `INSERT INTO users (username, email, password_hash, auth_provider)
     VALUES ($1, $2, $3, $4) RETURNING user_id`,
    [username, email, passwordHash, provider]
  );

exports.createGoogleUser = (username, email) =>
  db.query(
    `INSERT INTO users (username, email, auth_provider, email_verified, account_status)
     VALUES ($1, $2, 'google', TRUE, 'active') RETURNING user_id`,
    [username, email]
  );

exports.setEmailVerified = (userId) =>
  db.query(
    `UPDATE users SET email_verified = TRUE, account_status = 'active', updated_at = NOW()
     WHERE user_id = $1`,
    [userId]
  );

exports.updateLastLogin = (userId) =>
  db.query('UPDATE users SET last_login_at = NOW() WHERE user_id = $1', [userId]);

exports.updatePassword = (userId, passwordHash) =>
  db.query(
    'UPDATE users SET password_hash = $1, updated_at = NOW() WHERE user_id = $2',
    [passwordHash, userId]
  );

exports.updateUsername = (userId, username) =>
  db.query(
    'UPDATE users SET username = $1, updated_at = NOW() WHERE user_id = $2',
    [username, userId]
  );

exports.updateEmail = (userId, email) =>
  db.query(
    `UPDATE users SET email = $1, email_verified = FALSE,
            account_status = 'pending_verification', updated_at = NOW()
     WHERE user_id = $2`,
    [email, userId]
  );

// ── Profiles ─────────────────────────────────────────────────
exports.getProfile = (userId) =>
  db.query('SELECT * FROM user_profiles WHERE user_id = $1', [userId]);

exports.createProfile = (userId, displayName) =>
  db.query(
    'INSERT INTO user_profiles (user_id, display_name) VALUES ($1, $2)',
    [userId, displayName || null]
  );

exports.updateProfile = (userId, fields) => {
  const keys   = Object.keys(fields);
  const values = Object.values(fields);
  const sets   = keys.map((k, i) => `${k} = $${i + 2}`).join(', ');
  return db.query(
    `UPDATE user_profiles SET ${sets}, updated_at = NOW() WHERE user_id = $1 RETURNING *`,
    [userId, ...values]
  );
};

exports.updateProfilePicture = (userId, url) =>
  db.query(
    'UPDATE user_profiles SET profile_picture_url = $1, updated_at = NOW() WHERE user_id = $2',
    [url, userId]
  );

// ── Google Auth ───────────────────────────────────────────────
exports.findGoogleAccount = (googleId) =>
  db.query('SELECT user_id FROM google_auth WHERE google_id = $1 LIMIT 1', [googleId]);

exports.createGoogleAuth = (userId, googleId, googleEmail) =>
  db.query(
    'INSERT INTO google_auth (user_id, google_id, google_email) VALUES ($1, $2, $3)',
    [userId, googleId, googleEmail]
  );

// ── Verification ──────────────────────────────────────────────
exports.getLatestVerification = (userId) =>
  db.query(
    `SELECT id, code_hash, expires_at, attempts, resend_count
     FROM email_verification_codes
     WHERE user_id = $1 AND verified = FALSE
     ORDER BY created_at DESC LIMIT 1`,
    [userId]
  );

exports.insertVerificationCode = (userId, email, codeHash, expiresAt) =>
  db.query(
    `INSERT INTO email_verification_codes (user_id, email, code_hash, expires_at)
     VALUES ($1, $2, $3, $4)`,
    [userId, email, codeHash, expiresAt]
  );

exports.incrementVerifyAttempt = (id) =>
  db.query(
    'UPDATE email_verification_codes SET attempts = attempts + 1 WHERE id = $1',
    [id]
  );

exports.markVerified = (id) =>
  db.query('UPDATE email_verification_codes SET verified = TRUE WHERE id = $1', [id]);

// ── Password Reset ────────────────────────────────────────────
exports.insertResetToken = (userId, tokenHash, expiresAt) =>
  db.query(
    'INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES ($1, $2, $3)',
    [userId, tokenHash, expiresAt]
  );

exports.findResetToken = (tokenHash) =>
  db.query(
    'SELECT id, user_id, expires_at, used FROM password_reset_tokens WHERE token_hash = $1 LIMIT 1',
    [tokenHash]
  );

exports.consumeResetToken = (id) =>
  db.query('UPDATE password_reset_tokens SET used = TRUE WHERE id = $1', [id]);

// ── Refresh Tokens ────────────────────────────────────────────
exports.insertRefreshToken = (userId, tokenHash, expiresAt) =>
  db.query(
    'INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES ($1, $2, $3)',
    [userId, tokenHash, expiresAt]
  );

exports.findRefreshToken = (tokenHash) =>
  db.query(
    `SELECT token_id, user_id, expires_at, revoked
     FROM refresh_tokens WHERE token_hash = $1 LIMIT 1`,
    [tokenHash]
  );

exports.revokeRefreshToken = (tokenHash) =>
  db.query('UPDATE refresh_tokens SET revoked = TRUE WHERE token_hash = $1', [tokenHash]);

exports.revokeAllUserTokens = (userId) =>
  db.query('UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = $1', [userId]);
