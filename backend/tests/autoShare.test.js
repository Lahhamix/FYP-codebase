/**
 * Auto-share recipients tests
 * Covers: GET /auto-share/recipients, POST /auto-share/recipients,
 *         PATCH /auto-share/recipients/:id, DELETE /auto-share/recipients/:id.
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
// GET /auto-share/recipients
// ─────────────────────────────────────────────────────────────
describe('GET /auto-share/recipients', () => {
  it('returns an empty array for a new user', async () => {
    // Create a brand-new user who has no recipients
    const fresh = await createVerifiedUser();

    const res = await request(app)
      .get('/auto-share/recipients')
      .set('Authorization', `Bearer ${fresh.accessToken}`);

    expect(res.status).toBe(200);
    expect(Array.isArray(res.body)).toBe(true);
    expect(res.body).toHaveLength(0);
  });

  it('returns 401 without token', async () => {
    const res = await request(app).get('/auto-share/recipients');
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// POST /auto-share/recipients
// ─────────────────────────────────────────────────────────────
describe('POST /auto-share/recipients', () => {
  it('creates a new recipient → 201', async () => {
    const res = await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        recipient_name:  'Dr Smith',
        recipient_email: 'dr.smith@example.com',
        alerts_enabled:  true,
      });

    expect(res.status).toBe(201);
    expect(res.body).toHaveProperty('recipient_id');  // PK is recipient_id
    expect(res.body.recipient_name).toBe('Dr Smith');
    expect(res.body.recipient_email).toBe('dr.smith@example.com');
    expect(res.body.alerts_enabled).toBe(true);
  });

  it('defaults alerts_enabled to true when omitted', async () => {
    const res = await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        recipient_name:  'Default Alerts',
        recipient_email: 'default@example.com',
      });

    expect(res.status).toBe(201);
    expect(res.body.alerts_enabled).toBe(true);
  });

  it('allows alerts_enabled=false', async () => {
    const res = await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        recipient_name:  'Disabled Recipient',
        recipient_email: 'disabled@example.com',
        alerts_enabled:  false,
      });

    expect(res.status).toBe(201);
    expect(res.body.alerts_enabled).toBe(false);
  });

  it('rejects missing recipient_name → 400', async () => {
    const res = await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ recipient_email: 'no-name@example.com' });

    expect(res.status).toBe(400);
  });

  it('rejects missing recipient_email → 400', async () => {
    const res = await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ recipient_name: 'No Email' });

    expect(res.status).toBe(400);
  });

  it('rejects invalid recipient_email format → 400', async () => {
    const res = await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        recipient_name:  'Bad Email',
        recipient_email: 'not-valid-email',
      });

    expect(res.status).toBe(400);
  });

  it('returns 401 without token', async () => {
    const res = await request(app)
      .post('/auto-share/recipients')
      .send({ recipient_name: 'X', recipient_email: 'x@example.com' });

    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// GET /auto-share/recipients (after adding)
// ─────────────────────────────────────────────────────────────
describe('GET /auto-share/recipients (populated)', () => {
  it('returns the recipients created by this user', async () => {
    const res = await request(app)
      .get('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(200);
    expect(Array.isArray(res.body)).toBe(true);
    expect(res.body.length).toBeGreaterThan(0);
  });

  it('does not include recipients belonging to other users', async () => {
    // Other user adds a recipient
    await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${otherToken}`)
      .send({ recipient_name: 'Other User Recipient', recipient_email: 'other@example.com' });

    const res = await request(app)
      .get('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`);

    const names = res.body.map((r) => r.recipient_name);
    expect(names).not.toContain('Other User Recipient');
  });
});

// ─────────────────────────────────────────────────────────────
// PATCH /auto-share/recipients/:id
// ─────────────────────────────────────────────────────────────
describe('PATCH /auto-share/recipients/:id', () => {
  let recipientId;

  beforeAll(async () => {
    const res = await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ recipient_name: 'To Update', recipient_email: 'update@example.com' });
    recipientId = res.body.recipient_id;  // PK is recipient_id
  });

  it('updates recipient_name → 200', async () => {
    const res = await request(app)
      .patch(`/auto-share/recipients/${recipientId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ recipient_name: 'Updated Name' });

    expect(res.status).toBe(200);
    expect(res.body.recipient_name).toBe('Updated Name');
  });

  it('updates alerts_enabled to false → 200', async () => {
    const res = await request(app)
      .patch(`/auto-share/recipients/${recipientId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ alerts_enabled: false });

    expect(res.status).toBe(200);
    expect(res.body.alerts_enabled).toBe(false);
  });

  it('re-enables alerts → 200', async () => {
    const res = await request(app)
      .patch(`/auto-share/recipients/${recipientId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ alerts_enabled: true });

    expect(res.status).toBe(200);
  });

  it('cannot update another user\'s recipient', async () => {
    const res = await request(app)
      .patch(`/auto-share/recipients/${recipientId}`)
      .set('Authorization', `Bearer ${otherToken}`)
      .send({ recipient_name: 'Hijacked' });

    expect([403, 404]).toContain(res.status);
  });

  it('returns 401 without token', async () => {
    const res = await request(app)
      .patch(`/auto-share/recipients/${recipientId}`)
      .send({ alerts_enabled: false });
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// DELETE /auto-share/recipients/:id
// ─────────────────────────────────────────────────────────────
describe('DELETE /auto-share/recipients/:id', () => {
  it('removes the recipient → 200', async () => {
    const createRes = await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ recipient_name: 'DeleteMe', recipient_email: 'deleteme@example.com' });

    const recipientId = createRes.body.recipient_id;

    const delRes = await request(app)
      .delete(`/auto-share/recipients/${recipientId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(delRes.status).toBe(204);

    // Should no longer appear in the list
    const listRes = await request(app)
      .get('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`);

    const ids = listRes.body.map((r) => r.recipient_id);
    expect(ids).not.toContain(recipientId);
  });

  it('cannot delete another user\'s recipient', async () => {
    const createRes = await request(app)
      .post('/auto-share/recipients')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ recipient_name: 'Protected', recipient_email: 'protected@example.com' });

    const res = await request(app)
      .delete(`/auto-share/recipients/${createRes.body.recipient_id}`)
      .set('Authorization', `Bearer ${otherToken}`);

    expect([403, 404]).toContain(res.status);
  });

  it('returns 401 without token', async () => {
    const res = await request(app).delete('/auto-share/recipients/some-id');
    expect(res.status).toBe(401);
  });
});
