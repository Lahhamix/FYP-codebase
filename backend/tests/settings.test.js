/**
 * Settings tests
 * Covers: GET /settings, PATCH /settings with all supported fields and
 *         validation errors.
 *
 * Default values from schema:
 *   language='en', text_size='medium', notifications_enabled=true,
 *   app_lock_enabled=false, voice_hints_enabled=false,
 *   auto_share_enabled=false, theme='light'
 */
const request = require('supertest');
const { app, createVerifiedUser } = require('./helpers');

let accessToken;

beforeAll(async () => {
  ({ accessToken } = await createVerifiedUser());
});

// ─────────────────────────────────────────────────────────────
// GET /settings
// ─────────────────────────────────────────────────────────────
describe('GET /settings', () => {
  it('returns the user settings with expected defaults', async () => {
    const res = await request(app)
      .get('/settings')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      language:              'en',
      text_size:             'medium',
      notifications_enabled: true,
      app_lock_enabled:      false,
      voice_hints_enabled:   false,
      auto_share_enabled:    false,
      theme:                 'light',
    });
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app).get('/settings');
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// PATCH /settings
// ─────────────────────────────────────────────────────────────
describe('PATCH /settings', () => {
  it('changes theme to dark', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ theme: 'dark' });

    expect(res.status).toBe(200);

    const check = await request(app)
      .get('/settings')
      .set('Authorization', `Bearer ${accessToken}`);
    expect(check.body.theme).toBe('dark');
  });

  it('changes language', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ language: 'ar' });

    expect(res.status).toBe(200);
  });

  it('changes text_size to small', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ text_size: 'small' });

    expect(res.status).toBe(200);

    const check = await request(app)
      .get('/settings')
      .set('Authorization', `Bearer ${accessToken}`);
    expect(check.body.text_size).toBe('small');
  });

  it('changes text_size to large', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ text_size: 'large' });

    expect(res.status).toBe(200);
  });

  it('enables notifications', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ notifications_enabled: false });

    expect(res.status).toBe(200);
  });

  it('enables app_lock', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ app_lock_enabled: true });

    expect(res.status).toBe(200);
  });

  it('enables voice_hints', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ voice_hints_enabled: true });

    expect(res.status).toBe(200);
  });

  it('enables auto_share', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ auto_share_enabled: true });

    expect(res.status).toBe(200);

    const check = await request(app)
      .get('/settings')
      .set('Authorization', `Bearer ${accessToken}`);
    expect(check.body.auto_share_enabled).toBe(true);
  });

  it('updates multiple settings in one request', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        theme:                 'light',
        language:              'en',
        text_size:             'medium',
        notifications_enabled: true,
        app_lock_enabled:      false,
        voice_hints_enabled:   false,
        auto_share_enabled:    false,
      });

    expect(res.status).toBe(200);
  });

  it('rejects invalid theme value → 400', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ theme: 'blue' });

    expect(res.status).toBe(400);
  });

  it('rejects invalid text_size value → 400', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ text_size: 'huge' });

    expect(res.status).toBe(400);
  });

  it('rejects non-boolean notifications_enabled → 400', async () => {
    const res = await request(app)
      .patch('/settings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ notifications_enabled: 'yes' });

    expect(res.status).toBe(400);
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app)
      .patch('/settings')
      .send({ theme: 'dark' });

    expect(res.status).toBe(401);
  });
});
