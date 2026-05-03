/**
 * Runs via Jest "setupFiles" — executes in the worker before any module imports.
 * Loads the test env file (.env.test if present, otherwise .env) then forces
 * test-safe overrides so the suite never sends real emails or hits rate limits.
 */
const path = require('path');
const fs   = require('fs');

const testEnvPath = path.resolve(__dirname, '../.env.test');
const devEnvPath  = path.resolve(__dirname, '../.env');

require('dotenv').config({ path: fs.existsSync(testEnvPath) ? testEnvPath : devEnvPath });

// Force test-safe values regardless of what the env file contains
process.env.NODE_ENV       = 'test';
process.env.USE_LOCAL_EMAIL = 'true';  // log emails instead of sending via SendGrid

// Provide stub values for required vars that may not be in .env
if (!process.env.JWT_SECRET)       process.env.JWT_SECRET       = 'test-jwt-secret-at-least-32-chars-long-stub!!';
if (!process.env.REFRESH_SECRET)   process.env.REFRESH_SECRET   = 'test-refresh-secret-at-least-32-chars-stub!!';
if (!process.env.GOOGLE_CLIENT_ID) process.env.GOOGLE_CLIENT_ID = 'test-google-client-id.apps.googleusercontent.com';
