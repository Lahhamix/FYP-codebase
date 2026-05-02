const path = require('path');
require('dotenv').config({ path: path.resolve(__dirname, '../../.env') });

const required = [
  'DATABASE_URL',
  'JWT_SECRET',
  'REFRESH_SECRET',
  'SENDGRID_API_KEY',
  'SENDGRID_FROM_EMAIL',
  'GOOGLE_CLIENT_ID',
];

for (const key of required) {
  if (!process.env[key]) throw new Error(`Missing required env var: ${key}`);
}

module.exports = {
  NODE_ENV:            process.env.NODE_ENV || 'development',
  PORT:                Number(process.env.PORT) || 3000,
  DATABASE_URL:        process.env.DATABASE_URL,
  JWT_SECRET:          process.env.JWT_SECRET,
  JWT_EXPIRES_IN:      process.env.JWT_EXPIRES_IN || '15m',
  REFRESH_SECRET:      process.env.REFRESH_SECRET,
  REFRESH_EXPIRES_IN:  process.env.REFRESH_EXPIRES_IN || '30d',
  SENDGRID_API_KEY:    process.env.SENDGRID_API_KEY,
  SENDGRID_FROM_EMAIL: process.env.SENDGRID_FROM_EMAIL,
  SENDGRID_API_BASE:   (process.env.SENDGRID_API_BASE_URL || 'https://api.sendgrid.com').replace(/\/$/, ''),
  GOOGLE_CLIENT_ID:    process.env.GOOGLE_CLIENT_ID,
  UPLOAD_DIR:          process.env.UPLOAD_DIR || 'uploads',
};
