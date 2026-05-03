/**
 * User / Profile management tests
 * Covers: GET /users/me, PATCH /users/me, PATCH /users/me/username,
 *         PATCH /users/me/email, unauthenticated access.
 */
const request = require('supertest');
const { app, db, uniqueUser, createVerifiedUser } = require('./helpers');

// ─────────────────────────────────────────────────────────────
// GET /users/me
// ─────────────────────────────────────────────────────────────
describe('GET /users/me', () => {
  let accessToken, userData;

  beforeAll(async () => {
    ({ accessToken, userData } = await createVerifiedUser());
  });

  it('returns the user profile when authenticated', async () => {
    const res = await request(app)
      .get('/users/me')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      username: userData.username,
      email:    userData.email,
    });
    // The field is user_id (not id) at this endpoint
    expect(res.body).toHaveProperty('user_id');
  });

  it('includes a nested profile object with profile fields', async () => {
    const res = await request(app)
      .get('/users/me')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(200);
    // Top-level user fields
    expect(Object.keys(res.body)).toEqual(
      expect.arrayContaining(['username', 'email', 'user_id', 'profile']),
    );
    // Nested profile object exists (may have null fields initially)
    expect(res.body.profile).toBeDefined();
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app).get('/users/me');
    expect(res.status).toBe(401);
  });

  it('returns 401 with invalid token', async () => {
    const res = await request(app)
      .get('/users/me')
      .set('Authorization', 'Bearer not.a.valid.token');
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// PATCH /users/me  (profile fields)
// ─────────────────────────────────────────────────────────────
describe('PATCH /users/me', () => {
  let accessToken, userId;

  beforeAll(async () => {
    const result = await createVerifiedUser();
    accessToken  = result.accessToken;
    userId       = result.user.id;
  });

  it('updates display_name', async () => {
    const res = await request(app)
      .patch('/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ display_name: 'Test Display' });

    expect(res.status).toBe(200);

    const { rows } = await db.query(
      'SELECT display_name FROM user_profiles WHERE user_id = $1', [userId],
    );
    expect(rows[0].display_name).toBe('Test Display');
  });

  it('updates date_of_birth (ISO 8601 date)', async () => {
    const res = await request(app)
      .patch('/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ date_of_birth: '1990-05-15' });

    expect(res.status).toBe(200);

    const { rows } = await db.query(
      'SELECT date_of_birth FROM user_profiles WHERE user_id = $1', [userId],
    );
    expect(rows[0].date_of_birth).not.toBeNull();
  });

  it('updates gender', async () => {
    const res = await request(app)
      .patch('/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ gender: 'M' });

    expect(res.status).toBe(200);

    const { rows } = await db.query(
      'SELECT gender FROM user_profiles WHERE user_id = $1', [userId],
    );
    expect(rows[0].gender).toBe('M');
  });

  it('updates multiple profile fields at once', async () => {
    const res = await request(app)
      .patch('/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ display_name: 'Multi Update', gender: 'F', date_of_birth: '1995-01-01' });

    expect(res.status).toBe(200);
  });

  it('allows clearing display_name with null', async () => {
    const res = await request(app)
      .patch('/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ display_name: null });

    expect(res.status).toBe(200);
  });

  it('rejects date_of_birth in wrong format → 400', async () => {
    const res = await request(app)
      .patch('/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ date_of_birth: '15-05-1990' });   // dd-MM-yyyy not accepted

    expect(res.status).toBe(400);
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app)
      .patch('/users/me')
      .send({ display_name: 'X' });
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// PATCH /users/me/username
// ─────────────────────────────────────────────────────────────
describe('PATCH /users/me/username', () => {
  let accessToken, userId;

  beforeAll(async () => {
    const result = await createVerifiedUser();
    accessToken  = result.accessToken;
    userId       = result.user.id;
  });

  it('changes username successfully', async () => {
    const newUsername = `nu${Date.now()}`.slice(0, 30);

    const res = await request(app)
      .patch('/users/me/username')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ username: newUsername });

    expect(res.status).toBe(200);

    const { rows } = await db.query(
      'SELECT username FROM users WHERE user_id = $1', [userId],
    );
    expect(rows[0].username).toBe(newUsername);
  });

  it('returns 409 when username is already taken by another user', async () => {
    const { userData: other } = await createVerifiedUser();

    const res = await request(app)
      .patch('/users/me/username')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ username: other.username });

    expect(res.status).toBe(409);
  });

  it('rejects username shorter than 3 chars → 400', async () => {
    const res = await request(app)
      .patch('/users/me/username')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ username: 'ab' });
    expect(res.status).toBe(400);
  });

  it('rejects username with special characters → 400', async () => {
    const res = await request(app)
      .patch('/users/me/username')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ username: 'bad_name!' });
    expect(res.status).toBe(400);
  });

  it('rejects missing username field → 400', async () => {
    const res = await request(app)
      .patch('/users/me/username')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({});
    expect(res.status).toBe(400);
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app)
      .patch('/users/me/username')
      .send({ username: 'validname' });
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// PATCH /users/me/email
// ─────────────────────────────────────────────────────────────
describe('PATCH /users/me/email', () => {
  let accessToken, userId;

  beforeAll(async () => {
    const result = await createVerifiedUser();
    accessToken  = result.accessToken;
    userId       = result.user.id;
  });

  it('changes email and sets email_verified=false, account_status=pending_verification', async () => {
    const newEmail = uniqueUser().email;

    const res = await request(app)
      .patch('/users/me/email')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ email: newEmail });

    expect(res.status).toBe(200);

    const { rows } = await db.query(
      'SELECT email, email_verified, account_status FROM users WHERE user_id = $1',
      [userId],
    );
    expect(rows[0].email).toBe(newEmail);
    expect(rows[0].email_verified).toBe(false);
    expect(rows[0].account_status).toBe('pending_verification');
  });

  it('returns 409 when new email is taken by another user', async () => {
    const { userData: other } = await createVerifiedUser();

    // Create a fresh user since the previous test changed the email
    const fresh = await createVerifiedUser();

    const res = await request(app)
      .patch('/users/me/email')
      .set('Authorization', `Bearer ${fresh.accessToken}`)
      .send({ email: other.email });

    expect(res.status).toBe(409);
  });

  it('rejects invalid email format → 400', async () => {
    const fresh = await createVerifiedUser();
    const res = await request(app)
      .patch('/users/me/email')
      .set('Authorization', `Bearer ${fresh.accessToken}`)
      .send({ email: 'not-valid' });
    expect(res.status).toBe(400);
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app)
      .patch('/users/me/email')
      .send({ email: uniqueUser().email });
    expect(res.status).toBe(401);
  });
});
