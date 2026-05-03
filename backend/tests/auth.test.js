/**
 * Authentication flow tests
 * Covers: register, email-verify, resend, change-pending-email,
 *         login, token refresh, logout, forgot/reset password.
 */
const request = require('supertest');
const {
  app, db,
  uniqueUser, registerUser, setKnownCode, verifyEmail,
  loginUser, createVerifiedUser,
} = require('./helpers');

// ─────────────────────────────────────────────────────────────
// REGISTRATION
// ─────────────────────────────────────────────────────────────
describe('POST /auth/register', () => {
  it('returns 201 and a pending UUID on valid input', async () => {
    const user = uniqueUser();
    const res  = await registerUser(user);

    expect(res.status).toBe(201);
    expect(res.body).toHaveProperty('userId');
    expect(typeof res.body.userId).toBe('string');
    // userId is a UUID
    expect(res.body.userId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
  });

  it('creates a row in pending_registrations', async () => {
    const user = uniqueUser();
    const res  = await registerUser(user);

    const { rows } = await db.query(
      'SELECT * FROM pending_registrations WHERE id = $1',
      [res.body.userId],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0].email).toBe(user.email);
    expect(rows[0].username).toBe(user.username);
    expect(rows[0].attempts).toBe(0);
    expect(rows[0].resend_count).toBe(0);
    expect(new Date(rows[0].code_expires_at)).toBeInstanceOf(Date);
  });

  it('replaces a stale pending registration for the same email → 201', async () => {
    // The service intentionally deletes any existing pending row for the same email
    // so a user who lost their code can always re-register. 409 only fires when the
    // email already exists in the verified users table.
    const user = uniqueUser();
    const first  = await registerUser(user);   // creates pending
    const second = await registerUser(user);   // replaces the stale pending

    expect(first.status).toBe(201);
    expect(second.status).toBe(201);           // new pending replaces the old one
    // Only the latest pendingId should still exist
    const { rows } = await db.query(
      'SELECT id FROM pending_registrations WHERE email = $1', [user.email],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0].id).toBe(second.body.userId);
  });

  it('rejects duplicate username with 409 USERNAME_TAKEN', async () => {
    const a = uniqueUser();
    const b = uniqueUser();
    b.username = a.username;               // same username, different email

    await registerUser(a);
    const res = await registerUser(b);

    expect(res.status).toBe(409);
    expect(res.body.code).toBe('USERNAME_TAKEN');
  });

  it('rejects already-registered email (in users table) with 409', async () => {
    // Create a fully verified user first
    const { userData } = await createVerifiedUser();

    // Try to register again with the same email
    const res = await registerUser(userData);
    expect(res.status).toBe(409);
    expect(res.body.code).toBe('EMAIL_TAKEN');
  });

  it('rejects password without uppercase → 400', async () => {
    const user = { ...uniqueUser(), password: 'testpass@1' };
    const res  = await registerUser(user);
    expect(res.status).toBe(400);
  });

  it('rejects password without number → 400', async () => {
    const user = { ...uniqueUser(), password: 'TestPass@!' };
    const res  = await registerUser(user);
    expect(res.status).toBe(400);
  });

  it('rejects password without special character → 400', async () => {
    const user = { ...uniqueUser(), password: 'TestPass123' };
    const res  = await registerUser(user);
    expect(res.status).toBe(400);
  });

  it('rejects password shorter than 8 chars → 400', async () => {
    const user = { ...uniqueUser(), password: 'Tp@1' };
    const res  = await registerUser(user);
    expect(res.status).toBe(400);
  });

  it('rejects username shorter than 3 chars → 400', async () => {
    const user = { ...uniqueUser(), username: 'ab' };
    const res  = await registerUser(user);
    expect(res.status).toBe(400);
  });

  it('rejects username longer than 30 chars → 400', async () => {
    const user = { ...uniqueUser(), username: 'a'.repeat(31) };
    const res  = await registerUser(user);
    expect(res.status).toBe(400);
  });

  it('rejects non-alphanumeric username → 400', async () => {
    const user = { ...uniqueUser(), username: 'bad_user!' };
    const res  = await registerUser(user);
    expect(res.status).toBe(400);
  });

  it('rejects invalid email format → 400', async () => {
    const user = { ...uniqueUser(), email: 'not-an-email' };
    const res  = await registerUser(user);
    expect(res.status).toBe(400);
  });

  it('rejects missing fields → 400', async () => {
    const res = await request(app).post('/auth/register').send({ email: 'x@test.invalid' });
    expect(res.status).toBe(400);
  });
});

// ─────────────────────────────────────────────────────────────
// EMAIL VERIFICATION
// ─────────────────────────────────────────────────────────────
describe('POST /auth/verify-email', () => {
  it('returns tokens and user profile on valid code', async () => {
    const user = uniqueUser();
    const CODE = '111222';

    const regRes = await registerUser(user);
    await setKnownCode(regRes.body.userId, CODE);

    const res = await verifyEmail(regRes.body.userId, CODE);

    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('accessToken');
    expect(res.body).toHaveProperty('refreshToken');
    expect(res.body.user).toMatchObject({
      username: user.username,
      email:    user.email,
    });
    expect(res.body.user).toHaveProperty('id');
  });

  it('creates rows in users, user_profiles, user_settings and deletes pending', async () => {
    const user = uniqueUser();
    const CODE = '333444';

    const regRes = await registerUser(user);
    const pendingId = regRes.body.userId;
    await setKnownCode(pendingId, CODE);
    const verRes = await verifyEmail(pendingId, CODE);

    const userId = verRes.body.user.id;

    const { rows: userRows } = await db.query(
      'SELECT email_verified, account_status, auth_provider FROM users WHERE user_id = $1',
      [userId],
    );
    expect(userRows[0].email_verified).toBe(true);
    expect(userRows[0].account_status).toBe('active');
    expect(userRows[0].auth_provider).toBe('local');

    const { rows: profileRows } = await db.query(
      'SELECT user_id FROM user_profiles WHERE user_id = $1', [userId],
    );
    expect(profileRows).toHaveLength(1);

    const { rows: settingsRows } = await db.query(
      'SELECT language, theme FROM user_settings WHERE user_id = $1', [userId],
    );
    expect(settingsRows[0].language).toBe('en');
    expect(settingsRows[0].theme).toBe('light');

    const { rows: pendingRows } = await db.query(
      'SELECT id FROM pending_registrations WHERE id = $1', [pendingId],
    );
    expect(pendingRows).toHaveLength(0);
  });

  it('returns 400 INVALID_CODE on wrong code', async () => {
    const regRes = await registerUser(uniqueUser());
    const pendingId = regRes.body.userId;
    await setKnownCode(pendingId, '999999');

    const res = await verifyEmail(pendingId, '000000');
    expect(res.status).toBe(400);
    expect(res.body.code).toBe('INVALID_CODE');
  });

  it('increments attempts counter on wrong code', async () => {
    const regRes = await registerUser(uniqueUser());
    const pendingId = regRes.body.userId;
    await setKnownCode(pendingId, '999999');

    await verifyEmail(pendingId, '000000');

    const { rows } = await db.query(
      'SELECT attempts FROM pending_registrations WHERE id = $1', [pendingId],
    );
    expect(rows[0].attempts).toBe(1);
  });

  it('returns 400 CODE_EXPIRED when code is past expiry', async () => {
    const regRes = await registerUser(uniqueUser());
    const pendingId = regRes.body.userId;
    await setKnownCode(pendingId, '123456');

    // Force the code to be expired
    await db.query(
      `UPDATE pending_registrations
          SET code_expires_at = NOW() - INTERVAL '1 minute'
        WHERE id = $1`,
      [pendingId],
    );

    const res = await verifyEmail(pendingId, '123456');
    expect(res.status).toBe(400);
    expect(res.body.code).toBe('CODE_EXPIRED');
  });

  it('returns 429 after 5 failed attempts', async () => {
    const regRes = await registerUser(uniqueUser());
    const pendingId = regRes.body.userId;
    await setKnownCode(pendingId, '999999');

    // Simulate 5 prior failed attempts via DB
    await db.query(
      'UPDATE pending_registrations SET attempts = 5 WHERE id = $1',
      [pendingId],
    );

    const res = await verifyEmail(pendingId, '000000');
    expect(res.status).toBe(429);
  });

  it('returns 400 for non-existent pendingId', async () => {
    const res = await verifyEmail('00000000-0000-0000-0000-000000000000', '123456');
    expect(res.status).toBe(400);
  });

  it('rejects non-UUID pendingId → 400', async () => {
    const res = await verifyEmail('not-a-uuid', '123456');
    expect(res.status).toBe(400);
  });

  it('rejects code that is not 6 digits → 400', async () => {
    const regRes = await registerUser(uniqueUser());
    const res = await verifyEmail(regRes.body.userId, '12345');
    expect(res.status).toBe(400);
  });
});

// ─────────────────────────────────────────────────────────────
// RESEND VERIFICATION
// ─────────────────────────────────────────────────────────────
describe('POST /auth/resend-verification', () => {
  it('returns 200 and increments resend_count', async () => {
    const regRes = await registerUser(uniqueUser());
    const pendingId = regRes.body.userId;

    const res = await request(app)
      .post('/auth/resend-verification')
      .send({ userId: pendingId });

    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('message');

    const { rows } = await db.query(
      'SELECT resend_count FROM pending_registrations WHERE id = $1', [pendingId],
    );
    expect(rows[0].resend_count).toBe(1);
  });

  it('resets attempts to 0 on resend', async () => {
    const regRes = await registerUser(uniqueUser());
    const pendingId = regRes.body.userId;

    // Simulate some failed attempts
    await db.query(
      'UPDATE pending_registrations SET attempts = 3 WHERE id = $1', [pendingId],
    );

    await request(app).post('/auth/resend-verification').send({ userId: pendingId });

    const { rows } = await db.query(
      'SELECT attempts FROM pending_registrations WHERE id = $1', [pendingId],
    );
    expect(rows[0].attempts).toBe(0);
  });

  it('returns 429 when resend_count has reached 5', async () => {
    const regRes = await registerUser(uniqueUser());
    const pendingId = regRes.body.userId;

    await db.query(
      'UPDATE pending_registrations SET resend_count = 5 WHERE id = $1', [pendingId],
    );

    const res = await request(app)
      .post('/auth/resend-verification')
      .send({ userId: pendingId });

    expect(res.status).toBe(429);
  });

  it('returns 404 for unknown pendingId', async () => {
    const res = await request(app)
      .post('/auth/resend-verification')
      .send({ userId: '00000000-0000-0000-0000-000000000001' });

    expect(res.status).toBe(404);
  });
});

// ─────────────────────────────────────────────────────────────
// CHANGE PENDING EMAIL
// ─────────────────────────────────────────────────────────────
describe('POST /auth/change-pending-email', () => {
  it('updates email and generates a new code', async () => {
    const original = uniqueUser();
    const regRes   = await registerUser(original);
    const pendingId = regRes.body.userId;

    const newEmail = uniqueUser().email;
    const res = await request(app)
      .post('/auth/change-pending-email')
      .send({ userId: pendingId, email: newEmail });

    expect(res.status).toBe(200);

    const { rows } = await db.query(
      'SELECT email, resend_count FROM pending_registrations WHERE id = $1', [pendingId],
    );
    expect(rows[0].email).toBe(newEmail);
    expect(rows[0].resend_count).toBeGreaterThanOrEqual(1);
  });

  it('rejects if new email is already in users table → 409', async () => {
    const { userData: existing } = await createVerifiedUser();

    const regRes = await registerUser(uniqueUser());

    const res = await request(app)
      .post('/auth/change-pending-email')
      .send({ userId: regRes.body.userId, email: existing.email });

    expect(res.status).toBe(409);
  });

  it('returns 400 for unknown pendingId', async () => {
    const res = await request(app)
      .post('/auth/change-pending-email')
      .send({ userId: '00000000-0000-0000-0000-000000000002', email: uniqueUser().email });

    expect(res.status).toBe(400);
  });

  it('rejects invalid email format → 400', async () => {
    const regRes = await registerUser(uniqueUser());
    const res = await request(app)
      .post('/auth/change-pending-email')
      .send({ userId: regRes.body.userId, email: 'bad-email' });

    expect(res.status).toBe(400);
  });
});

// ─────────────────────────────────────────────────────────────
// LOGIN
// ─────────────────────────────────────────────────────────────
describe('POST /auth/login', () => {
  let verifiedUser;

  beforeAll(async () => {
    verifiedUser = await createVerifiedUser();
  });

  it('succeeds with email + correct password → 200 + tokens + user', async () => {
    const res = await loginUser(verifiedUser.userData.email, verifiedUser.userData.password);

    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('accessToken');
    expect(res.body).toHaveProperty('refreshToken');
    expect(res.body.user).toMatchObject({
      username: verifiedUser.userData.username,
      email:    verifiedUser.userData.email,
    });
  });

  it('succeeds with username + correct password → 200', async () => {
    const res = await loginUser(
      verifiedUser.userData.username,
      verifiedUser.userData.password,
    );
    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('accessToken');
  });

  it('returns 401 on wrong password', async () => {
    const res = await loginUser(verifiedUser.userData.email, 'WrongPass@999');
    expect(res.status).toBe(401);
  });

  it('returns 401 for non-existent identifier', async () => {
    const res = await loginUser('ghost@test.invalid', 'AnyPass@123');
    expect(res.status).toBe(401);
  });

  it('returns 400 on missing identifier → validation error', async () => {
    const res = await request(app)
      .post('/auth/login')
      .send({ password: 'TestPass@123' });
    expect(res.status).toBe(400);
  });

  it('returns 400 on missing password → validation error', async () => {
    const res = await request(app)
      .post('/auth/login')
      .send({ identifier: 'someone@test.invalid' });
    expect(res.status).toBe(400);
  });

  it('updates last_login_at on successful login', async () => {
    await loginUser(verifiedUser.userData.email, verifiedUser.userData.password);

    const { rows } = await db.query(
      'SELECT last_login_at FROM users WHERE email = $1',
      [verifiedUser.userData.email],
    );
    expect(rows[0].last_login_at).not.toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────
// TOKEN REFRESH
// ─────────────────────────────────────────────────────────────
describe('POST /auth/refresh', () => {
  it('issues new accessToken with valid refreshToken → 200', async () => {
    const { refreshToken } = await createVerifiedUser();

    const res = await request(app)
      .post('/auth/refresh')
      .send({ refreshToken });

    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('accessToken');
  });

  it('returns 401 on invalid refreshToken', async () => {
    const res = await request(app)
      .post('/auth/refresh')
      .send({ refreshToken: 'completely-fake-token' });

    expect(res.status).toBe(401);
  });

  it('returns 401 when the same refreshToken is used twice (rotation)', async () => {
    const { refreshToken } = await createVerifiedUser();

    // First use
    await request(app).post('/auth/refresh').send({ refreshToken });

    // Second use of the same token should fail
    const res = await request(app)
      .post('/auth/refresh')
      .send({ refreshToken });

    expect(res.status).toBe(401);
  });

  it('returns 400 when refreshToken field is missing', async () => {
    const res = await request(app).post('/auth/refresh').send({});
    expect(res.status).toBe(400);
  });
});

// ─────────────────────────────────────────────────────────────
// LOGOUT
// ─────────────────────────────────────────────────────────────
describe('POST /auth/logout', () => {
  it('returns 200 and revokes the refreshToken', async () => {
    const { accessToken, refreshToken } = await createVerifiedUser();

    const logoutRes = await request(app)
      .post('/auth/logout')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ refreshToken });

    expect(logoutRes.status).toBe(200);

    // Refresh should now fail
    const refreshRes = await request(app)
      .post('/auth/refresh')
      .send({ refreshToken });
    expect(refreshRes.status).toBe(401);
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app)
      .post('/auth/logout')
      .send({ refreshToken: 'any-token' });
    expect(res.status).toBe(401);
  });

  it('returns 401 with expired / invalid access token', async () => {
    const res = await request(app)
      .post('/auth/logout')
      .set('Authorization', 'Bearer not.a.valid.jwt')
      .send({});
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// FORGOT / RESET PASSWORD
// ─────────────────────────────────────────────────────────────
describe('POST /auth/forgot-password and /auth/reset-password', () => {
  it('forgot-password returns 200 even for unknown email (no info leak)', async () => {
    const res = await request(app)
      .post('/auth/forgot-password')
      .send({ identifier: 'nobody@test.invalid' });

    expect(res.status).toBe(200);
  });

  it('forgot-password returns 200 for known email', async () => {
    const { userData } = await createVerifiedUser();

    const res = await request(app)
      .post('/auth/forgot-password')
      .send({ identifier: userData.email });

    expect(res.status).toBe(200);
  });

  it('reset-password returns 400 for invalid / fake token', async () => {
    const res = await request(app)
      .post('/auth/reset-password')
      .send({ token: 'invalidtoken', password: 'NewPass@123' });

    expect(res.status).toBe(400);
  });

  it('forgot-password requires identifier field → 400 on empty body', async () => {
    const res = await request(app)
      .post('/auth/forgot-password')
      .send({});
    expect(res.status).toBe(400);
  });

  it('reset-password rejects weak new password → 400', async () => {
    const res = await request(app)
      .post('/auth/reset-password')
      .send({ token: 'anytoken', password: 'weak' });

    expect(res.status).toBe(400);
  });
});

// ─────────────────────────────────────────────────────────────
// GOOGLE SIGN-IN (error paths only — real token requires OAuth)
// ─────────────────────────────────────────────────────────────
describe('POST /auth/google', () => {
  it('returns 401 for an invalid/fake idToken', async () => {
    const res = await request(app)
      .post('/auth/google')
      .send({ idToken: 'fake.google.token' });

    expect(res.status).toBe(401);
  });

  it('returns 400 when idToken field is missing', async () => {
    const res = await request(app).post('/auth/google').send({});
    expect(res.status).toBe(400);
  });
});
