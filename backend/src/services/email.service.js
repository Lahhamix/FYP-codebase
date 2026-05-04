const env = require('../config/env');
const fs = require('fs');
const path = require('path');

async function send({ to, subject, text, html }) {
  if (env.USE_LOCAL_EMAIL) {
    const entry = [
      '---',
      new Date().toISOString(),
      `To: ${to}`,
      `Subject: ${subject}`,
      text,
      '',
    ].join('\n');
    const logPath = path.resolve(__dirname, '../../email_log.txt');
    fs.appendFileSync(logPath, entry, 'utf8');
    console.log(`[EMAIL:local] To: ${to} | Subject: ${subject} | logged to ${logPath}`);
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

function esc(s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function shareVerificationHtml(patientName, code) {
  const n = esc(patientName || 'SoleMate user');
  const c = esc(code);
  return `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Email Verification - SoleMate</title></head>
<body style="font-family:Arial,sans-serif;margin:0;padding:0;background:#f4f4f9;color:#333">
<div style="max-width:600px;margin:0 auto;background:#fff;padding:30px;border-radius:8px;box-shadow:0 0 15px rgba(0,0,0,.1)">
  <h1 style="color:#0E3B66;font-size:26px;text-align:center">Verify Your Email Address</h1>
  <p>Hello,</p>
  <p>We are reaching out because <strong>${n}</strong> has added you to share health data via the SoleMate system. To ensure security, please verify your email address.</p>
  <p>Please use the following verification code to confirm your email:</p>
  <h2 style="text-align:center;font-size:30px;font-weight:bold;letter-spacing:6px;color:#0E3B66;background:#f0f4f8;padding:18px;border-radius:8px">${c}</h2>
  <p>Once the patient enters the code in the app, you will gain access to the shared data.</p>
  <p>If you did not request access or this message was sent in error, please disregard this email.</p>
  <p style="font-size:13px;color:#666;font-style:italic">Note: This verification code will expire in 10 minutes.</p>
</div></body></html>`;
}

function sharingStoppedHtml(patientName) {
  const n = esc(patientName || 'The patient');
  return `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Alert Access Update - SoleMate</title></head>
<body style="font-family:Arial,sans-serif;margin:0;padding:0;background:#f4f4f9;color:#333">
<div style="max-width:600px;margin:0 auto;background:#fff;padding:30px;border-radius:8px;box-shadow:0 0 15px rgba(0,0,0,.08)">
  <div style="text-align:center;padding-bottom:15px;border-bottom:2px solid #0E3B66">
    <h1 style="color:#0E3B66;font-size:24px;margin:0">Alert Sharing Update</h1>
  </div>
  <p style="font-size:16px;line-height:1.6">Hello,</p>
  <p style="font-size:16px;line-height:1.6">We would like to inform you that your alert access through the SoleMate system has been updated.</p>
  <div style="background:#f1f5f9;border-left:4px solid #0E3B66;padding:15px;margin:20px 0;border-radius:5px;font-size:16px;line-height:1.6">
    <strong>${n}</strong> has stopped sharing health alerts with you.
  </div>
  <p style="font-size:16px;line-height:1.6;font-weight:bold;color:#0E3B66">You will no longer receive email notifications when alerts are triggered.</p>
  <p style="font-size:16px;line-height:1.6">If this change was unexpected or you believe you should still receive alerts, please contact the patient directly.</p>
  <p style="font-size:13px;color:#666;margin-top:20px;text-align:center">This is an automated message. No action is required.</p>
</div></body></html>`;
}

function sharingDisabledHtml(patientName) {
  const n = esc(patientName || 'The patient');
  return `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Alert Sharing Disabled - SoleMate</title></head>
<body style="font-family:Arial,sans-serif;margin:0;padding:0;background:#f4f4f9;color:#333">
<div style="max-width:600px;margin:0 auto;background:#fff;padding:30px;border-radius:8px;box-shadow:0 0 15px rgba(0,0,0,.08)">
  <div style="text-align:center;padding-bottom:15px;border-bottom:2px solid #0E3B66">
    <h1 style="color:#0E3B66;font-size:24px;margin:0">Alert Sharing Disabled</h1>
  </div>
  <p style="font-size:16px;line-height:1.6">Hello,</p>
  <p style="font-size:16px;line-height:1.6">We would like to inform you that <strong>${n}</strong> has turned off alert sharing in the SoleMate Application.</p>
  <div style="background:#f1f5f9;border-left:4px solid #0E3B66;padding:15px;margin:20px 0;border-radius:5px;font-size:16px;line-height:1.6">
    As a result, you will no longer receive email notifications when alerts are triggered for this user.
  </div>
  <p style="font-size:16px;line-height:1.6">If you believe this change was made unintentionally, please contact the user directly.</p>
  <p style="font-size:13px;color:#666;margin-top:20px;text-align:center">This is an automated message. No action is required.</p>
</div></body></html>`;
}

exports.send = send;

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

exports.sendShareVerification = (to, patientName, code) =>
  send({
    to,
    subject: 'SoleMate Email Verification',
    text:    `${patientName || 'A SoleMate user'} has added you to share health data.\nVerification code: ${code}\n\nThis code expires in 10 minutes.`,
    html:    shareVerificationHtml(patientName, code),
  });

exports.sendSharingStoppedEmail = (to, patientName) =>
  send({
    to,
    subject: 'Alert Sharing Update - SoleMate',
    text:    `${patientName || 'A SoleMate user'} has stopped sharing health alerts with you.\n\nYou will no longer receive email notifications when alerts are triggered.\n\nThis is an automated message. No action is required.`,
    html:    sharingStoppedHtml(patientName),
  });

exports.sendAlertSharingDisabledEmail = (to, patientName) =>
  send({
    to,
    subject: 'Alert Sharing Disabled - SoleMate',
    text:    `${patientName || 'A SoleMate user'} has turned off alert sharing in the SoleMate Application.\n\nAs a result, you will no longer receive email notifications when alerts are triggered for this user.\n\nThis is an automated message. No action is required.`,
    html:    sharingDisabledHtml(patientName),
  });
