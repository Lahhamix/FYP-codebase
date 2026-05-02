const bcrypt       = require('bcrypt');
const crypto       = require('crypto');
const { OAuth2Client } = require('google-auth-library');
const db           = require('../config/db');
const jwtHelper    = require('../config/jwt');
const env          = require('../config/env');
const userModel    = require('../models/user.model');
const settingsModel= require('../models/settings.model');
const emailService = require('./email.service');

const SALT_ROUNDS = 12;
const googleClient = new OAuth2Client(env.GOOGLE_CLIENT_ID);

// ── Helpers ───────────────────────────────────────────────────
function appError(message, status, code) {
  return Object.assign(new Error(message), { status, code });
}

function sha256(str) {
  return crypto.createHash('sha256').update(str).digest('hex');
}

async function issueTokens(userId) {
  const accessToken  = jwtHelper.signAccess(userId);
  const rawRefresh   = crypto.randomBytes(40).toString('hex');
  const refreshHash  = sha256(rawRefresh);
  const expiresAt    = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  await userModel.insertRefreshToken(userId, refreshHash, expiresAt);
  return { accessToken, refreshToken: rawRefresh };
}

async function issueTokensWithUser(userId) {
  const tokens   = await issueTokens(userId);
  const { rows } = await userModel.findByIdWithProfile(userId);
  const u        = rows[0];
  return {
    ...tokens,
    user: {
      id:                userId,
      username:          u.username,
      email:             u.email,
      displayName:       u.display_name       || null,
      profilePictureUrl: u.profile_picture_url || null,
      dateOfBirth:       u.date_of_birth       || null,
      gender:            u.gender              || null,
    },
  };
}

async function generateUniqueUsername(base) {
  const clean = (base || 'user').replace(/\s+/g, '').toLowerCase().slice(0, 20) || 'user';
  let candidate = clean;
  let suffix = 0;
  while (true) {
    const { rows } = await userModel.findByUsername(candidate);
    if (!rows.length) return candidate;
    candidate = `${clean}${++suffix}`;
  }
}

// ── Register ──────────────────────────────────────────────────
async function register({ username, email, password }) {
  const emailInUsers = await userModel.findByEmail(email);
  if (emailInUsers.rows.length) throw appError('Email already registered.', 409, 'EMAIL_TAKEN');

  const usernameInUsers = await userModel.findByUsername(username);
  if (usernameInUsers.rows.length) throw appError('Username already taken.', 409, 'USERNAME_TAKEN');

  // Delete any stale pending for this email so expired codes never block re-registration
  await userModel.deletePendingByEmail(email);

  const activePendingUsername = await userModel.findActivePendingByUsername(username);
  if (activePendingUsername.rows.length) {
    throw appError('Username already taken.', 409, 'USERNAME_TAKEN');
  }

  const pendingId     = crypto.randomUUID();
  const passwordHash  = await bcrypt.hash(password, SALT_ROUNDS);
  const code          = crypto.randomInt(100000, 999999).toString();
  const codeHash      = await bcrypt.hash(code, 10);
  const codeExpiresAt = new Date(Date.now() + 10 * 60 * 1000);

  await userModel.insertPending(pendingId, username, email, passwordHash, codeHash, codeExpiresAt);

  await emailService.sendVerificationCode(email, username, code);

  return pendingId;
}

// ── Email Verification ────────────────────────────────────────
async function sendVerificationCode(pendingId) {
  const { rows } = await userModel.findPendingById(pendingId);
  if (!rows.length) throw appError('No pending registration found.', 404);

  const pending = rows[0];
  if (pending.resend_count >= 5) throw appError('Too many resend attempts.', 429);

  const code          = crypto.randomInt(100000, 999999).toString();
  const codeHash      = await bcrypt.hash(code, 10);
  const codeExpiresAt = new Date(Date.now() + 10 * 60 * 1000);

  await userModel.updatePendingCode(pendingId, codeHash, codeExpiresAt);
  await emailService.sendVerificationCode(pending.email, pending.username, code);
}

async function verifyEmail(pendingId, code) {
  const { rows } = await userModel.findPendingById(pendingId);
  if (!rows.length) throw appError('No pending registration. Please register first.', 400);

  const pending = rows[0];
  if (new Date() > pending.code_expires_at) throw appError('Code expired. Please request a new one.', 400, 'CODE_EXPIRED');
  if (pending.attempts >= 5)                throw appError('Too many failed attempts. Request a new code.', 429);

  const valid = await bcrypt.compare(code, pending.code_hash);
  if (!valid) {
    await userModel.incrementPendingAttempt(pendingId);
    throw appError('Invalid verification code.', 400, 'INVALID_CODE');
  }

  const { rows: userRows } = await userModel.createVerified(pending.username, pending.email, pending.password_hash);
  const userId = userRows[0].user_id;

  await userModel.createProfile(userId, null);
  await settingsModel.create(userId);
  await userModel.deletePending(pendingId);

  return issueTokensWithUser(userId);
}

// ── Change Pending Email (before verification) ────────────────
async function changePendingEmail(pendingId, newEmail) {
  const { rows } = await userModel.findPendingById(pendingId);
  if (!rows.length) throw appError('No pending registration found.', 400);

  const pending = rows[0];

  const emailInUsers = await userModel.findByEmail(newEmail);
  if (emailInUsers.rows.length) throw appError('Email already registered.', 409, 'EMAIL_TAKEN');

  const activePending = await userModel.findActivePendingByEmail(newEmail);
  if (activePending.rows.length && activePending.rows[0].id !== pendingId) {
    throw appError('Email already registered.', 409, 'EMAIL_TAKEN');
  }

  const code          = crypto.randomInt(100000, 999999).toString();
  const codeHash      = await bcrypt.hash(code, 10);
  const codeExpiresAt = new Date(Date.now() + 10 * 60 * 1000);

  await userModel.updatePendingEmail(pendingId, newEmail, codeHash, codeExpiresAt);
  await emailService.sendVerificationCode(newEmail, pending.username, code);
}

// ── Login ─────────────────────────────────────────────────────
async function login(identifier, password) {
  const { rows } = await userModel.findByEmailOrUsername(identifier);
  if (!rows.length) throw appError('Invalid credentials.', 401);

  const user = rows[0];
  if (user.account_status === 'disabled') throw appError('Account is disabled.', 403);
  if (user.account_status === 'deleted')  throw appError('Account not found.', 404);
  if (user.auth_provider !== 'local')     throw appError('Please sign in with Google.', 403);

  const valid = await bcrypt.compare(password, user.password_hash);
  if (!valid) throw appError('Invalid credentials.', 401);

  await userModel.updateLastLogin(user.user_id);
  return issueTokensWithUser(user.user_id);
}

// ── Google Sign-In ────────────────────────────────────────────
async function googleSignIn(idToken) {
  const ticket = await googleClient.verifyIdToken({
    idToken,
    audience: env.GOOGLE_CLIENT_ID,
  }).catch(() => { throw appError('Invalid Google token.', 401); });

  const { sub: googleId, email: googleEmail, name } = ticket.getPayload();

  const existing = await userModel.findGoogleAccount(googleId);
  if (existing.rows.length) {
    const userId = existing.rows[0].user_id;
    await userModel.updateLastLogin(userId);
    return issueTokensWithUser(userId);
  }

  const emailCheck = await userModel.findByEmail(googleEmail);
  if (emailCheck.rows.length) {
    throw appError('An account with this email already exists. Please log in with your password.', 409);
  }

  const username = await generateUniqueUsername(name);
  const { rows } = await userModel.createGoogleUser(username, googleEmail);
  const userId   = rows[0].user_id;

  await userModel.createGoogleAuth(userId, googleId, googleEmail);
  await userModel.createProfile(userId, name);
  await settingsModel.create(userId);
  await userModel.updateLastLogin(userId);

  return issueTokensWithUser(userId);
}

// ── Forgot / Reset Password ───────────────────────────────────
async function forgotPassword(identifier) {
  const { rows } = await userModel.findByEmailOrUsername(identifier);
  if (!rows.length || rows[0].auth_provider !== 'local') return;

  const user      = rows[0];
  const rawToken  = crypto.randomBytes(32).toString('hex');
  const tokenHash = sha256(rawToken);
  const expiresAt = new Date(Date.now() + 30 * 60 * 1000);

  await userModel.insertResetToken(user.user_id, tokenHash, expiresAt);
  await emailService.sendPasswordReset(user.email, rawToken);
}

async function resetPassword(rawToken, newPassword) {
  const tokenHash = sha256(rawToken);
  const { rows }  = await userModel.findResetToken(tokenHash);

  if (!rows.length || rows[0].used || new Date() > rows[0].expires_at) {
    throw appError('Invalid or expired reset token.', 400);
  }

  const passwordHash = await bcrypt.hash(newPassword, SALT_ROUNDS);
  await userModel.updatePassword(rows[0].user_id, passwordHash);
  await userModel.consumeResetToken(rows[0].id);
  await userModel.revokeAllUserTokens(rows[0].user_id);
}

// ── Refresh Token ─────────────────────────────────────────────
async function refreshTokens(rawRefresh) {
  const tokenHash = sha256(rawRefresh);
  const { rows }  = await userModel.findRefreshToken(tokenHash);

  if (!rows.length || rows[0].revoked || new Date() > rows[0].expires_at) {
    throw appError('Invalid or expired refresh token.', 401);
  }

  await userModel.revokeRefreshToken(tokenHash);
  return issueTokens(rows[0].user_id);
}

// ── Logout ────────────────────────────────────────────────────
async function logout(rawRefresh) {
  if (!rawRefresh) return;
  const tokenHash = sha256(rawRefresh);
  await userModel.revokeRefreshToken(tokenHash);
}

module.exports = {
  register, sendVerificationCode, verifyEmail, changePendingEmail,
  login, googleSignIn,
  forgotPassword, resetPassword,
  refreshTokens, logout,
};
