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

exports.createVerified = (username, email, passwordHash) =>
  db.query(
    `INSERT INTO users (username, email, password_hash, auth_provider, email_verified, account_status)
     VALUES ($1, $2, $3, 'local', TRUE, 'active') RETURNING user_id`,
    [username, email, passwordHash]
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

// ── User + Profile (used for auth responses) ──────────────────
exports.findByIdWithProfile = (userId) =>
  db.query(
    `SELECT u.user_id, u.username, u.email,
            p.display_name, p.profile_picture_url, p.date_of_birth, p.gender
     FROM users u
     LEFT JOIN user_profiles p ON p.user_id = u.user_id
     WHERE u.user_id = $1`,
    [userId]
  );

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

// ── Pending Registrations ─────────────────────────────────────
exports.findPendingById = (id) =>
  db.query('SELECT * FROM pending_registrations WHERE id = $1 LIMIT 1', [id]);

exports.findActivePendingByEmail = (email) =>
  db.query(
    `SELECT * FROM pending_registrations
     WHERE email = $1 AND created_at > NOW() - INTERVAL '24 hours' LIMIT 1`,
    [email]
  );

exports.findActivePendingByUsername = (username) =>
  db.query(
    `SELECT * FROM pending_registrations
     WHERE username = $1 AND created_at > NOW() - INTERVAL '24 hours' LIMIT 1`,
    [username]
  );

exports.insertPending = (username, email, passwordHash, codeHash, codeExpiresAt) =>
  db.query(
    `INSERT INTO pending_registrations (username, email, password_hash, code_hash, code_expires_at)
     VALUES ($1, $2, $3, $4, $5) RETURNING id`,
    [username, email, passwordHash, codeHash, codeExpiresAt]
  );

exports.deletePendingByEmail = (email) =>
  db.query('DELETE FROM pending_registrations WHERE email = $1', [email]);

exports.updatePendingCode = (id, codeHash, codeExpiresAt) =>
  db.query(
    `UPDATE pending_registrations
     SET code_hash = $2, code_expires_at = $3, resend_count = resend_count + 1, attempts = 0
     WHERE id = $1`,
    [id, codeHash, codeExpiresAt]
  );

exports.updatePendingEmail = (id, email, codeHash, codeExpiresAt) =>
  db.query(
    `UPDATE pending_registrations
     SET email = $2, code_hash = $3, code_expires_at = $4, resend_count = resend_count + 1, attempts = 0
     WHERE id = $1`,
    [id, email, codeHash, codeExpiresAt]
  );

exports.incrementPendingAttempt = (id) =>
  db.query('UPDATE pending_registrations SET attempts = attempts + 1 WHERE id = $1', [id]);

exports.deletePending = (id) =>
  db.query('DELETE FROM pending_registrations WHERE id = $1', [id]);
