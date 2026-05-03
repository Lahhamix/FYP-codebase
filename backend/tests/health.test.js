const request = require('supertest');
const { app }  = require('./helpers');

describe('GET /health', () => {
  it('returns ok:true and version 2.0.0', async () => {
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ ok: true, version: '2.0.0' });
  });
});
