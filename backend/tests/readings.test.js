/**
 * Health readings tests.
 * Covers: POST /readings, GET /readings/latest, GET /readings/history.
 *
 * Sensor field reference:
 *   heart_rate     Integer 40-200
 *   spo2           Number 0-100
 *   bp_systolic    Integer 60-200
 *   bp_diastolic   Integer 40-120
 *   swelling_value Numeric score or edema label
 *   step_count     Integer 0-999999
 *   motion_status  String "0" | "1"
 *   recorded_at    ISO 8601 UTC
 */
const request = require('supertest');
const { app, db, createVerifiedUser } = require('./helpers');

let accessToken, userId;

beforeAll(async () => {
  const result = await createVerifiedUser();
  accessToken = result.accessToken;
  userId = result.user.id;
});

describe('POST /readings', () => {
  it('creates a reading with all sensor fields', async () => {
    const payload = {
      heart_rate: 78,
      spo2: 98,
      bp_systolic: 120,
      bp_diastolic: 80,
      swelling_value: 0.5,
      step_count: 1250,
      motion_status: '1',
      recorded_at: new Date().toISOString(),
    };

    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send(payload);

    expect(res.status).toBe(201);
    expect(res.body).toHaveProperty('reading_id');
  });

  it('stores the correct user_id in health_readings', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ heart_rate: 72, recorded_at: new Date().toISOString() });

    expect(res.status).toBe(201);

    const { rows } = await db.query(
      'SELECT user_id FROM health_readings WHERE reading_id = $1',
      [res.body.reading_id],
    );
    expect(rows[0].user_id).toBe(userId);
  });

  it('rejects a device_id owned by another user', async () => {
    const other = await createVerifiedUser();
    const device = await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${other.accessToken}`)
      .send({ ble_name: 'Other User Device' });

    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ device_id: device.body.device_id, heart_rate: 72 });

    expect(res.status).toBe(404);
  });

  it('creates a heart-rate-only reading', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ heart_rate: 90 });

    expect(res.status).toBe(201);
  });

  it('creates an SpO2-only reading', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ spo2: 97 });

    expect(res.status).toBe(201);
  });

  it('creates a decimal SpO2 reading from Android BLE payloads', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ spo2: 98.5 });

    expect(res.status).toBe(201);
  });

  it('creates a blood-pressure reading', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ bp_systolic: 130, bp_diastolic: 85 });

    expect(res.status).toBe(201);
  });

  it('creates a step-count reading', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ step_count: 3500 });

    expect(res.status).toBe(201);
  });

  it('creates a numeric swelling/edema reading', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ swelling_value: 1.2 });

    expect(res.status).toBe(201);
  });

  it('creates a swelling label reading from firmware classification', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ swelling_value: 'mild' });

    expect(res.status).toBe(201);
  });

  it('creates a motion-status reading', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ motion_status: '0' });

    expect(res.status).toBe(201);
  });

  it('uses the provided recorded_at timestamp', async () => {
    const ts = '2026-01-15T10:30:00Z';
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ heart_rate: 65, recorded_at: ts });

    expect(res.status).toBe(201);

    const { rows } = await db.query(
      'SELECT recorded_at FROM health_readings WHERE reading_id = $1',
      [res.body.reading_id],
    );
    expect(new Date(rows[0].recorded_at).toISOString()).toBe(new Date(ts).toISOString());
  });

  it('rejects heart_rate above allowed max', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ heart_rate: 999 });

    expect(res.status).toBe(400);
  });

  it('rejects spo2 above 100', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ spo2: 105 });

    expect(res.status).toBe(400);
  });

  it('rejects invalid recorded_at format', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ heart_rate: 75, recorded_at: 'not-a-date' });

    expect(res.status).toBe(400);
  });

  it('rejects empty reading payloads', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({});

    expect(res.status).toBe(400);
  });

  it('rejects readings where every sensor value is null or empty', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        heart_rate: null,
        spo2: null,
        swelling_value: '',
        recorded_at: new Date().toISOString(),
      });

    expect(res.status).toBe(400);
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app)
      .post('/readings')
      .send({ heart_rate: 72 });

    expect(res.status).toBe(401);
  });

  it('returns 401 with invalid token', async () => {
    const res = await request(app)
      .post('/readings')
      .set('Authorization', 'Bearer bad.token.here')
      .send({ heart_rate: 72 });

    expect(res.status).toBe(401);
  });
});

describe('GET /readings/latest', () => {
  it('returns the most recent reading for the authenticated user', async () => {
    await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ heart_rate: 55, recorded_at: new Date().toISOString() });

    const res = await request(app)
      .get('/readings/latest')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(200);
    expect(res.body).toBeDefined();
  });

  it('returns 401 without token', async () => {
    const res = await request(app).get('/readings/latest');
    expect(res.status).toBe(401);
  });
});

describe('GET /readings/history', () => {
  it('returns an array of readings within the date range', async () => {
    const from = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString();
    const to = new Date().toISOString();

    const res = await request(app)
      .get('/readings/history')
      .set('Authorization', `Bearer ${accessToken}`)
      .query({ from, to });

    expect(res.status).toBe(200);
    expect(Array.isArray(res.body)).toBe(true);
  });

  it('returns an empty array when no readings exist in the range', async () => {
    const from = '2000-01-01T00:00:00Z';
    const to = '2000-01-02T00:00:00Z';

    const res = await request(app)
      .get('/readings/history')
      .set('Authorization', `Bearer ${accessToken}`)
      .query({ from, to });

    expect(res.status).toBe(200);
    expect(Array.isArray(res.body)).toBe(true);
    expect(res.body).toHaveLength(0);
  });

  it('only returns readings belonging to the authenticated user', async () => {
    const other = await createVerifiedUser();

    await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${other.accessToken}`)
      .send({ heart_rate: 77 });

    const from = new Date(Date.now() - 60 * 1000).toISOString();
    const to = new Date(Date.now() + 60 * 1000).toISOString();

    const res = await request(app)
      .get('/readings/history')
      .set('Authorization', `Bearer ${accessToken}`)
      .query({ from, to });

    expect(res.status).toBe(200);
    res.body.forEach((r) => {
      expect(r.user_id).toBe(userId);
    });
  });

  it('returns 400 when from or to is missing', async () => {
    const res = await request(app)
      .get('/readings/history')
      .set('Authorization', `Bearer ${accessToken}`)
      .query({ from: new Date().toISOString() });

    expect(res.status).toBe(400);
  });

  it('returns 400 when date format is invalid', async () => {
    const res = await request(app)
      .get('/readings/history')
      .set('Authorization', `Bearer ${accessToken}`)
      .query({ from: 'bad-date', to: 'bad-date' });

    expect(res.status).toBe(400);
  });

  it('returns 401 without token', async () => {
    const res = await request(app)
      .get('/readings/history')
      .query({ from: '2026-01-01T00:00:00Z', to: '2026-12-31T00:00:00Z' });

    expect(res.status).toBe(401);
  });
});
