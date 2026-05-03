/**
 * Shared test helpers.
 *
 * All test accounts use the "jesttest_*@example.com" pattern so they are
 * trivially identifiable and safely deleted in setup.js.
 * example.com is RFC 2606 reserved — Joi accepts it but no real user has it.
 *
 * The "verified user" flow works by:
 *   1. POST /auth/register  → get pendingId
 *   2. Directly overwrite the pending code in the DB with a known value
 *   3. POST /auth/verify-email with that known code → get JWT tokens
 *
 * This avoids any dependency on email delivery and is deterministic.
 */
const request = require('supertest');
const bcrypt  = require('bcrypt');
const app     = require('../src/app');
const db      = require('../src/config/db');

let _counter = 0;

/** Returns a unique set of registration credentials. */
function uniqueUser() {
  const stamp = `${Date.now()}${++_counter}`;
  return {
    username: `tu${stamp}`.slice(0, 30),
    email:    `jesttest_${stamp}@example.com`,
    password: 'TestPass@123',
  };
}

/** POST /auth/register */
async function registerUser(userData) {
  return request(app).post('/auth/register').send(userData);
}

/**
 * Overwrite the pending_registrations code with a known plaintext value so
 * tests can verify without intercepting the email.  Uses bcrypt rounds=4 for
 * speed; this is never used in production.
 */
async function setKnownCode(pendingId, code = '123456') {
  const hash    = await bcrypt.hash(code, 4);
  const expires = new Date(Date.now() + 10 * 60 * 1000);
  await db.query(
    `UPDATE pending_registrations
        SET code_hash = $1, code_expires_at = $2
      WHERE id = $3`,
    [hash, expires, pendingId],
  );
  return code;
}

/** POST /auth/verify-email */
async function verifyEmail(pendingId, code) {
  return request(app).post('/auth/verify-email').send({ userId: pendingId, code });
}

/** POST /auth/login */
async function loginUser(identifier, password) {
  return request(app).post('/auth/login').send({ identifier, password });
}

/**
 * Full register-then-verify flow.
 * Returns the verify-email response body: { accessToken, refreshToken, user }.
 * Also attaches `userData` (the credentials used) for downstream assertions.
 */
async function createVerifiedUser(overrides = {}) {
  const userData  = { ...uniqueUser(), ...overrides };
  const KNOWN_CODE = '654321';

  const regRes = await registerUser(userData);
  if (regRes.status !== 201) {
    throw new Error(`Register failed (${regRes.status}): ${JSON.stringify(regRes.body)}`);
  }

  const pendingId = regRes.body.userId;
  await setKnownCode(pendingId, KNOWN_CODE);

  const verRes = await verifyEmail(pendingId, KNOWN_CODE);
  if (verRes.status !== 200) {
    throw new Error(`Verify failed (${verRes.status}): ${JSON.stringify(verRes.body)}`);
  }

  return { ...verRes.body, userData };
}

/** Convenience: returns just the Bearer token string for authenticated requests. */
async function getAccessToken(overrides = {}) {
  const { accessToken } = await createVerifiedUser(overrides);
  return accessToken;
}

module.exports = {
  app,
  db,
  uniqueUser,
  registerUser,
  setKnownCode,
  verifyEmail,
  loginUser,
  createVerifiedUser,
  getAccessToken,
};
