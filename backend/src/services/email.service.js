const env = require('../config/env');

async function send({ to, subject, text, html }) {
  if (env.USE_LOCAL_EMAIL) {
    console.log(`[EMAIL] To: ${to} | Subject: ${subject}\n${text}`);
    return;
  }
  const res = await fetch(`${env.SENDGRID_API_BASE}/v3/mail/send`, {
    method:  'POST',
    headers: {
      Authorization:  `Bearer ${env.SENDGRID_API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      personalizations: [{ to: [{ email: to }] }],
      from:    { email: env.SENDGRID_FROM_EMAIL, name: 'SoleMate' },
      subject,
      content: [
        { type: 'text/plain', value: text },
        { type: 'text/html',  value: html },
      ],
    }),
  });

  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    let msg = raw;
    try { msg = JSON.parse(raw)?.errors?.[0]?.message || raw; } catch { /* keep raw */ }
    throw Object.assign(new Error(`SendGrid error: ${msg}`), { status: 502 });
  }
}

function verificationHtml(username, code) {
  return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
  body{font-family:Arial,sans-serif;background:#f4f4f9;color:#333;margin:0;padding:0}
  .wrap{max-width:600px;margin:0 auto;background:#fff;padding:30px;border-radius:8px}
  .header{text-align:center;border-bottom:2px solid #0E3B66;padding-bottom:15px}
  h1{color:#0E3B66;font-size:24px;margin:0}
  .code{color:#0E3B66;font-size:30px;font-weight:bold;letter-spacing:6px;text-align:center;padding:18px;margin:25px 0;border-radius:8px;background:#f0f4f8}
  .note{font-size:13px;color:#666;margin-top:20px;text-align:center}
  .footer{text-align:center;font-size:14px;color:#777;margin-top:35px}
</style></head>
<body><div class="wrap">
  <div class="header"><h1>Verify Your Email</h1></div>
  <p>Dear ${username || 'SoleMate user'},</p>
  <p>Thank you for signing up. Enter the code below in the app to complete registration.</p>
  <div class="code">${code}</div>
  <p>If you did not create a SoleMate account, ignore this email.</p>
  <div class="footer"><p>SoleMate Team</p></div>
  <p class="note">This code expires in 10 minutes.</p>
</div></body></html>`;
}

function resetHtml(resetUrl) {
  return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
  body{font-family:Arial,sans-serif;background:#f4f4f9;color:#333;margin:0;padding:0}
  .wrap{max-width:600px;margin:0 auto;background:#fff;padding:30px;border-radius:8px}
  .header{text-align:center;border-bottom:2px solid #0E3B66;padding-bottom:15px}
  h1{color:#0E3B66;font-size:24px;margin:0}
  .btn{display:inline-block;background:#0E3B66;color:#fff;text-decoration:none;padding:14px 28px;border-radius:6px;font-size:16px;margin:25px 0}
  .note{font-size:13px;color:#666;margin-top:20px;text-align:center}
</style></head>
<body><div class="wrap">
  <div class="header"><h1>Reset Your Password</h1></div>
  <p>We received a request to reset your SoleMate password.</p>
  <p>Use this token in the app to set a new password:</p>
  <p style="word-break:break-all;font-family:monospace;background:#f0f4f8;padding:12px;border-radius:4px">${resetUrl}</p>
  <p>If you did not request this, ignore this email. The token expires in 30 minutes.</p>
  <div class="footer"><p>SoleMate Team</p></div>
</div></body></html>`;
}

exports.sendVerificationCode = (to, username, code) =>
  send({
    to,
    subject: 'Verify Your SoleMate Account',
    text:    `Your SoleMate verification code is: ${code}. It expires in 10 minutes.`,
    html:    verificationHtml(username, code),
  });

exports.sendPasswordReset = (to, rawToken) =>
  send({
    to,
    subject: 'Reset Your SoleMate Password',
    text:    `Your password reset token is: ${rawToken}\n\nIt expires in 30 minutes.`,
    html:    resetHtml(rawToken),
  });
