/**
 * Alerts tests
 * Covers: POST /alerts, GET /alerts, PATCH /alerts/:id/status.
 *
 * Alert types from the spec:
 *   heart_rate out of range (< 60 or > 100 BPM)
 *   SpO2 low (< 95%)
 *   Blood pressure abnormal
 *   Swelling detected
 */
const request = require('supertest');
const { app, createVerifiedUser } = require('./helpers');

let accessToken, otherToken;

beforeAll(async () => {
  const user  = await createVerifiedUser();
  const other = await createVerifiedUser();
  accessToken = user.accessToken;
  otherToken  = other.accessToken;
});

// ─────────────────────────────────────────────────────────────
// POST /alerts
// ─────────────────────────────────────────────────────────────
describe('POST /alerts', () => {
  it('creates a heart-rate alert → 201', async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'heart_rate',
        severity:   'warning',
        message:    'High Heart Rate: 156 BPM',
      });

    expect(res.status).toBe(201);
    expect(res.body).toHaveProperty('alert_id');
    expect(res.body.alert_type).toBe('heart_rate');
  });

  it('creates a SpO2 alert → 201', async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'spo2',
        severity:   'critical',
        message:    'Low SpO2: 91%',
      });

    expect(res.status).toBe(201);
  });

  it('creates a blood-pressure alert → 201', async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'blood_pressure',
        severity:   'warning',
        message:    'High BP: 180/110 mmHg',
      });

    expect(res.status).toBe(201);
  });

  it('creates a swelling alert → 201', async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'swelling',
        severity:   'info',
        message:    'Swelling detected: value 2.1',
      });

    expect(res.status).toBe(201);
  });

  it('uses "info" severity as default when not provided', async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'heart_rate',
        message:    'HR alert with no severity',
      });

    expect(res.status).toBe(201);
    expect(res.body.severity).toBe('info');
  });

  it('accepts an optional reading_id belonging to the authenticated user', async () => {
    const reading = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ heart_rate: 71 });

    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'heart_rate',
        message:    'Alert with reading link',
        reading_id: reading.body.reading_id,
      });

    expect(res.status).toBe(201);
  });

  it('rejects a missing reading_id instead of falling through to a FK error', async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'heart_rate',
        message:    'Alert with missing reading link',
        reading_id: '00000000-0000-0000-0000-000000000000',
      });

    expect(res.status).toBe(404);
  });

  it('rejects a reading_id owned by another user', async () => {
    const reading = await request(app)
      .post('/readings')
      .set('Authorization', `Bearer ${otherToken}`)
      .send({ heart_rate: 73 });

    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'heart_rate',
        message:    'Cross-user reading link',
        reading_id: reading.body.reading_id,
      });

    expect(res.status).toBe(404);
  });

  it('rejects missing alert_type → 400', async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ message: 'No type' });

    expect(res.status).toBe(400);
  });

  it('rejects missing message → 400', async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ alert_type: 'heart_rate' });

    expect(res.status).toBe(400);
  });

  it('rejects invalid severity value → 400', async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'heart_rate',
        message:    'Bad severity',
        severity:   'extreme',
      });

    expect(res.status).toBe(400);
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app)
      .post('/alerts')
      .send({ alert_type: 'heart_rate', message: 'Test' });

    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// GET /alerts
// ─────────────────────────────────────────────────────────────
describe('GET /alerts', () => {
  it('returns an array of alerts for the authenticated user', async () => {
    const res = await request(app)
      .get('/alerts')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(200);
    expect(Array.isArray(res.body)).toBe(true);
    expect(res.body.length).toBeGreaterThan(0);
  });

  it('does not include alerts from other users', async () => {
    // Create an alert for the other user
    await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${otherToken}`)
      .send({ alert_type: 'spo2', message: 'Other user alert' });

    const res = await request(app)
      .get('/alerts')
      .set('Authorization', `Bearer ${accessToken}`);

    const messages = res.body.map((a) => a.message);
    expect(messages).not.toContain('Other user alert');
  });

  it('returns 401 without token', async () => {
    const res = await request(app).get('/alerts');
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// PATCH /alerts/:id/status
// ─────────────────────────────────────────────────────────────
describe('PATCH /alerts/:id/status', () => {
  let alertId;

  beforeAll(async () => {
    const res = await request(app)
      .post('/alerts')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        alert_type: 'heart_rate',
        message:    'Alert to mark sent',
        severity:   'warning',
      });
    alertId = res.body.alert_id;
  });

  it('marks an alert as sent (sent=true) → 200', async () => {
    const res = await request(app)
      .patch(`/alerts/${alertId}/status`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ sent: true });

    expect(res.status).toBe(200);
  });

  it('marks an alert as not-sent (sent=false) → 200', async () => {
    const res = await request(app)
      .patch(`/alerts/${alertId}/status`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ sent: false });

    expect(res.status).toBe(200);
  });

  it('rejects missing sent field → 400', async () => {
    const res = await request(app)
      .patch(`/alerts/${alertId}/status`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({});

    expect(res.status).toBe(400);
  });

  it('rejects non-boolean sent value → 400', async () => {
    const res = await request(app)
      .patch(`/alerts/${alertId}/status`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ sent: 'yes' });

    expect(res.status).toBe(400);
  });

  it('cannot update another user\'s alert', async () => {
    const res = await request(app)
      .patch(`/alerts/${alertId}/status`)
      .set('Authorization', `Bearer ${otherToken}`)
      .send({ sent: true });

    expect([403, 404]).toContain(res.status);
  });

  it('returns 401 without token', async () => {
    const res = await request(app)
      .patch(`/alerts/${alertId}/status`)
      .send({ sent: true });
    expect(res.status).toBe(401);
  });
});
