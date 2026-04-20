const path = require('path');
const envPath = path.resolve(__dirname, '.env');
require('dotenv').config({ path: envPath, override: true });

const http = require('http');
const { URL } = require('url');

const PORT = Number(process.env.PORT || 3000);
const SENDGRID_API_KEY = (process.env.SENDGRID_API_KEY || '').trim();
const SENDGRID_FROM_EMAIL = (process.env.SENDGRID_FROM_EMAIL || '').trim();
const SENDGRID_API_BASE_URL = (process.env.SENDGRID_API_BASE_URL || 'https://api.sendgrid.com').trim().replace(/\/$/, '');

function setCorsHeaders(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS, GET');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

function sendJson(res, statusCode, payload) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(payload));
}

function collectRequestBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => {
      body += chunk;
      if (body.length > 1_000_000) {
        req.destroy();
        reject(new Error('Request body too large'));
      }
    });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

async function sendViaSendGrid({ to, subject, text, html }) {
  if (!SENDGRID_API_KEY || !SENDGRID_FROM_EMAIL) {
    return { ok: false, statusCode: 500, error: 'Missing SENDGRID_API_KEY or SENDGRID_FROM_EMAIL' };
  }

  const response = await fetch(`${SENDGRID_API_BASE_URL}/v3/mail/send`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${SENDGRID_API_KEY}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      personalizations: [{ to: [{ email: to }] }],
      from: { email: SENDGRID_FROM_EMAIL, name: 'SoleMate' },
      subject,
      content: [
        { type: 'text/plain', value: text },
        { type: 'text/html', value: html }
      ]
    })
  });

  if (!response.ok) {
    const raw = await response.text();
    let message = raw;
    try {
      const parsed = JSON.parse(raw);
      message = parsed?.errors?.[0]?.message || parsed?.error || raw;
    } catch (_) {
      // keep raw body
    }
    return { ok: false, statusCode: response.status, error: message };
  }

  return { ok: true, statusCode: response.status };
}

const server = http.createServer(async (req, res) => {
  setCorsHeaders(res);

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.method === 'GET' && req.url === '/health') {
    sendJson(res, 200, { ok: true });
    return;
  }

  if (req.method === 'POST' && req.url === '/send-email') {
    try {
      const body = await collectRequestBody(req);
      const payload = JSON.parse(body || '{}');
      const to = String(payload.to || '').trim();
      const subject = String(payload.subject || '').trim();
      const text = String(payload.text || '').trim();
      const html = String(payload.html || '').trim();

      if (!to || !subject || !text || !html) {
        sendJson(res, 400, { ok: false, error: 'Missing to, subject, text, or html' });
        return;
      }

      const result = await sendViaSendGrid({ to, subject, text, html });
      if (!result.ok) {
        sendJson(res, result.statusCode || 500, { ok: false, error: result.error || 'Failed to send email' });
        return;
      }

      sendJson(res, 200, { ok: true });
      return;
    } catch (error) {
      sendJson(res, 500, { ok: false, error: error.message || 'Server error' });
      return;
    }
  }

  sendJson(res, 404, { ok: false, error: 'Not found' });
});

server.listen(PORT, () => {
  console.log(`SoleMate email backend listening on port ${PORT}`);
});