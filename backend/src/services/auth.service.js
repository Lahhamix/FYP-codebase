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
  const tokens      = await issueTokens(userId);
  const { rows }    = await userModel.findById(userId);
  const u           = rows[0];
  return { ...tokens, user: { id: userId, username: u.username, email: u.email } };
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
  const emailCheck = await userModel.findByEmail(email);
  if (emailCheck.rows.length) throw appError('Email already registered.', 409, 'EMAIL_TAKEN');

  const usernameCheck = await userModel.findByUsername(username);
  if (usernameCheck.rows.length) throw appError('Username already taken.', 409, 'USERNAME_TAKEN');

  const passwordHash = await bcrypt.hash(password, SALT_ROUNDS);
  const { rows } = await userModel.create(username, email, passwordHash, 'local');
  const userId = rows[0].user_id;

  await userModel.createProfile(userId, null);
  await settingsModel.create(userId);
  await sendVerificationCode(userId, email, username);

  return userId;
}

// ── Email Verification ────────────────────────────────────────
async function sendVerificationCode(userId, email, username) {
  const existing = await userModel.getLatestVerification(userId);
  if (existing.rows.length) {
    const rec = existing.rows[0];
    if (rec.resend_count >= 5) throw appError('Too many resend attempts.', 429);
  }

  const code      = crypto.randomInt(100000, 999999).toString();
  const codeHash  = await bcrypt.hash(code, 10);
  const expiresAt = new Date(Date.now() + 10 * 60 * 1000);

  await userModel.insertVerificationCode(userId, email, codeHash, expiresAt);

  const userRes = await userModel.findById(userId);
  const uname   = username || userRes.rows[0]?.username || 'SoleMate user';
  await emailService.sendVerificationCode(email, uname, code);
}

async function verifyEmail(userId, code) {
  const { rows } = await userModel.getLatestVerification(userId);
  if (!rows.length) throw appError('No pending verification. Please register first.', 400);

  const rec = rows[0];
  if (new Date() > rec.expires_at) throw appError('Code expired. Please request a new one.', 400, 'CODE_EXPIRED');
  if (rec.attempts >= 5)           throw appError('Too many failed attempts. Request a new code.', 429);

  const valid = await bcrypt.compare(code, rec.code_hash);
  if (!valid) {
    await userModel.incrementVerifyAttempt(rec.id);
    throw appError('Invalid verification code.', 400, 'INVALID_CODE');
  }

  await userModel.markVerified(rec.id);
  await userModel.setEmailVerified(userId);
  return issueTokensWithUser(userId);
}

// ── Login ─────────────────────────────────────────────────────
async function login(identifier, password) {
  const { rows } = await userModel.findByEmailOrUsername(identifier);
  if (!rows.length) throw appError('Invalid credentials.', 401);

  const user = rows[0];
  if (user.account_status === 'disabled') throw appError('Account is disabled.', 403);
  if (user.account_status === 'deleted')  throw appError('Account not found.', 404);
  if (!user.email_verified)               throw appError('Email not verified. Please check your inbox.', 403, 'EMAIL_UNVERIFIED');
  if (user.auth_provider !== 'local')     throw appError('Please sign in with Google.', 400);

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

// ── Change Pending Email (before verification) ────────────────
async function changePendingEmail(userId, newEmail) {
  const userRes = await userModel.findById(userId);
  const user    = userRes.rows[0];
  if (!user || user.email_verified) throw appError('Cannot change email.', 400);

  const emailCheck = await userModel.findByEmail(newEmail);
  if (emailCheck.rows.length) throw appError('Email already registered.', 409, 'EMAIL_TAKEN');

  await db.query('UPDATE users SET email = $1 WHERE user_id = $2', [newEmail, userId]);
  await sendVerificationCode(userId, newEmail, user.username);
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
