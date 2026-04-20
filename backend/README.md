# SoleMate Email Backend

This service relays email requests from the Android app to SendGrid.

## 1) Configure environment

Create `.env` in this folder (or copy from `.env.example`):

```
PORT=3000
SENDGRID_API_KEY=your_sendgrid_api_key_here
SENDGRID_FROM_EMAIL=your_verified_sender@example.com
SENDGRID_API_BASE_URL=https://api.sendgrid.com
```

## 2) Start backend

```bash
npm install
npm start
```

Backend health check:

- `GET http://localhost:3000/health` should return `{ "ok": true }`

## 3) Make app use one shared backend URL

Set one public backend URL in project `gradle.properties` (checked into git):

```
EMAIL_BACKEND_BASE_URL=https://your-public-backend.example.com
EMAIL_BACKEND_BASE_URL_DEBUG=https://your-public-backend.example.com
EMAIL_BACKEND_BASE_URL_RELEASE=https://your-public-backend.example.com
```

Notes:
- `EMAIL_BACKEND_BASE_URL_DEBUG` and `EMAIL_BACKEND_BASE_URL_RELEASE` override `EMAIL_BACKEND_BASE_URL` if set.
- Do not commit backend secrets or `.env`.
- App email sending now uses backend relay only (no direct SendGrid fallback from Android).
