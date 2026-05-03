/**
 * Device management tests
 * Covers: POST /devices, GET /devices, PATCH /devices/:id, DELETE /devices/:id,
 *         access-control (user cannot touch another user's device).
 */
const request = require('supertest');
const { app, db, createVerifiedUser } = require('./helpers');

let accessToken, userId;
let otherToken;

beforeAll(async () => {
  const user  = await createVerifiedUser();
  const other = await createVerifiedUser();
  accessToken = user.accessToken;
  userId      = user.user.id;
  otherToken  = other.accessToken;
});

// ─────────────────────────────────────────────────────────────
// POST /devices
// ─────────────────────────────────────────────────────────────
describe('POST /devices', () => {
  it('creates a device and returns 201 with device data', async () => {
    const res = await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        ble_name:         'SoleMate Insole',
        ble_mac:          'AA:BB:CC:DD:EE:FF',
        firmware_version: '1.2.3',
      });

    expect(res.status).toBe(201);
    expect(res.body).toHaveProperty('device_id');
    expect(res.body.ble_name).toBe('SoleMate Insole');
    expect(res.body.device_status).toBe('active');
  });

  it('stores the device with the correct user_id', async () => {
    const res = await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ ble_name: 'Test Device' });

    const { rows } = await db.query(
      'SELECT user_id FROM devices WHERE device_id = $1',
      [res.body.device_id],
    );
    expect(rows[0].user_id).toBe(userId);
  });

  it('creates a device without optional fields (mac / firmware)', async () => {
    const res = await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ ble_name: 'Minimal Device' });

    expect(res.status).toBe(201);
  });

  it('rejects missing ble_name → 400', async () => {
    const res = await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ ble_mac: 'AA:BB:CC:DD:EE:FF' });

    expect(res.status).toBe(400);
  });

  it('returns 401 without Authorization header', async () => {
    const res = await request(app)
      .post('/devices')
      .send({ ble_name: 'Ghost' });
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// GET /devices
// ─────────────────────────────────────────────────────────────
describe('GET /devices', () => {
  it('returns only the authenticated user\'s devices', async () => {
    // Create a device for the test user
    await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ ble_name: 'My Device' });

    // Create a device for the OTHER user
    await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${otherToken}`)
      .send({ ble_name: 'Other Device' });

    const res = await request(app)
      .get('/devices')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(200);
    expect(Array.isArray(res.body)).toBe(true);

    // All returned devices must belong to this user
    res.body.forEach((d) => {
      expect(d.user_id).toBe(userId);
    });

    // Must NOT include the other user's device
    const names = res.body.map((d) => d.ble_name);
    expect(names).not.toContain('Other Device');
  });

  it('returns 401 without token', async () => {
    const res = await request(app).get('/devices');
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// PATCH /devices/:id
// ─────────────────────────────────────────────────────────────
describe('PATCH /devices/:id', () => {
  let deviceId;

  beforeAll(async () => {
    const res = await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ ble_name: 'PatchMe', ble_mac: '11:22:33:44:55:66' });
    deviceId = res.body.device_id;
  });

  it('updates ble_name', async () => {
    const res = await request(app)
      .patch(`/devices/${deviceId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ ble_name: 'Renamed Device' });

    expect(res.status).toBe(200);

    const { rows } = await db.query(
      'SELECT ble_name FROM devices WHERE device_id = $1', [deviceId],
    );
    expect(rows[0].ble_name).toBe('Renamed Device');
  });

  it('updates firmware_version', async () => {
    const res = await request(app)
      .patch(`/devices/${deviceId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ firmware_version: '2.0.0' });

    expect(res.status).toBe(200);
  });

  it('updates device_status to inactive', async () => {
    const res = await request(app)
      .patch(`/devices/${deviceId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ device_status: 'inactive' });

    expect(res.status).toBe(200);
  });

  it('rejects invalid device_status → 400', async () => {
    const res = await request(app)
      .patch(`/devices/${deviceId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ device_status: 'exploded' });

    expect(res.status).toBe(400);
  });

  it('cannot update a device belonging to another user', async () => {
    const res = await request(app)
      .patch(`/devices/${deviceId}`)
      .set('Authorization', `Bearer ${otherToken}`)
      .send({ ble_name: 'Hijacked' });

    // Expect 404 (not found for this user) or 403 (forbidden)
    expect([403, 404]).toContain(res.status);
  });

  it('returns 401 without token', async () => {
    const res = await request(app)
      .patch(`/devices/${deviceId}`)
      .send({ ble_name: 'X' });
    expect(res.status).toBe(401);
  });
});

// ─────────────────────────────────────────────────────────────
// DELETE /devices/:id
// ─────────────────────────────────────────────────────────────
describe('DELETE /devices/:id', () => {
  it('soft-deletes the device (sets status=removed)', async () => {
    const createRes = await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ ble_name: 'DeleteMe' });

    const deviceId = createRes.body.device_id;

    const delRes = await request(app)
      .delete(`/devices/${deviceId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(delRes.status).toBe(204);

    const { rows } = await db.query(
      'SELECT device_status FROM devices WHERE device_id = $1', [deviceId],
    );
    // Soft-delete should set status to 'removed'
    expect(rows[0].device_status).toBe('removed');
  });

  it('cannot delete another user\'s device', async () => {
    const createRes = await request(app)
      .post('/devices')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ ble_name: 'Protected' });

    const res = await request(app)
      .delete(`/devices/${createRes.body.device_id}`)
      .set('Authorization', `Bearer ${otherToken}`);

    expect([403, 404]).toContain(res.status);
  });

  it('returns 401 without token', async () => {
    const res = await request(app).delete('/devices/some-id');
    expect(res.status).toBe(401);
  });
});
