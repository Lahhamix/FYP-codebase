# SoleMate App - Complete Expected Behavior

This document describes what **SHOULD** happen in the app - the correct database behavior, not the current broken state.

---

## 1. AUTHENTICATION & ACCOUNTS

### Sign Up Flow
1. User enters: email, username, password, confirm password
2. **Validation (Client)**:
   - Email must be valid format
   - Username: 3-30 alphanumeric characters
   - Password: min 8 chars, must have uppercase, lowercase, number, special char
   - Password & confirm must match
3. **API Call**: POST `/auth/register` with `{username, email, password}`
4. **Backend Checks**:
   - ✅ Email NOT in `users` table
   - ✅ Email NOT in `pending_registrations` table (active)
   - ✅ Username NOT in `users` table
   - ✅ Username NOT in `pending_registrations` table (active)
   - If email exists in `pending_registrations`, delete it (stale)
5. **Success (HTTP 201)**:
   - Create row in `pending_registrations` with: id (UUID), username, email, password_hash (bcrypt), code_hash, code_expires_at (10 min), resend_count=0, attempts=0
   - Generate 6-digit verification code
   - Send verification code via **email** (SendGrid or local log)
   - Return `{userId: pendingId}`
   - Save `pendingId` to SharedPreferences as `KEY_PENDING_USER_ID`
6. **Navigate to**: Email Verification Screen

---

### Email Verification Flow
1. User receives verification code in email
2. User enters 6-digit code in app
3. **API Call**: POST `/auth/verify-email` with `{userId: pendingId, code: "123456"}`
4. **Backend Checks**:
   - ✅ `pending_registrations` row exists with this ID
   - ✅ Code not expired (`code_expires_at > NOW()`)
   - ✅ Not too many attempts (`attempts < 5`)
   - ✅ Code matches stored hash (bcrypt)
5. **On Invalid Code**:
   - ✅ Increment `attempts` field
   - ✅ Return HTTP 400 with error code `INVALID_CODE`
   - After 5 attempts: HTTP 429 `TOO_MANY_ATTEMPTS`
6. **On Expired Code**:
   - ✅ Return HTTP 400 with error code `CODE_EXPIRED`
7. **On Valid Code**:
   - ✅ Create new row in `users` table with: username, email, password_hash, auth_provider='local', email_verified=TRUE, account_status='active'
   - ✅ Create row in `user_profiles` with: user_id, display_name=NULL, profile_picture_url=NULL
   - ✅ Create row in `user_settings` with: user_id, all settings=defaults
   - ✅ Delete row from `pending_registrations`
   - Issue JWT tokens: accessToken (15m), refreshToken (30d)
   - Return user profile data: `{accessToken, refreshToken, user: {id, username, email, displayName, profilePictureUrl, dateOfBirth, gender}}`
   - Save to SessionManager
   - **Navigate to**: Main Dashboard (MainActivity)

---

### Resend Verification Code
1. User clicks "Resend Code"
2. **UI Cooldown**: Show 60-second timer, disable button
3. **API Call**: POST `/auth/resend-verification` with `{userId: pendingId}`
4. **Backend Checks**:
   - ✅ `pending_registrations` row exists
   - ✅ `resend_count < 5` (max 5 resends)
5. **On Success**:
   - ✅ Increment `resend_count`
   - ✅ Generate new 6-digit code
   - ✅ Update `code_hash` and `code_expires_at` (new 10 min expiration)
   - ✅ Reset `attempts = 0`
   - ✅ Send verification email
   - ✅ Return HTTP 200

---

### Change Email Before Verification Complete
1. User clicks "Change Email" on verification screen
2. Dialog shows current email, allow user to enter new email
3. **Validation**: New email must be valid format
4. **API Call**: POST `/auth/change-pending-email` with `{userId: pendingId, email: newEmail}`
5. **Backend Checks**:
   - ✅ Pending registration exists
   - ✅ New email NOT in `users` table
   - ✅ New email NOT in another active `pending_registrations`
6. **On Success**:
   - ✅ Update `pending_registrations.email` = newEmail
   - ✅ Generate new code
   - ✅ Update `code_hash` and `code_expires_at`
   - ✅ Reset `attempts = 0`, increment `resend_count`
   - ✅ Send verification code to **NEW email**
   - ✅ Return HTTP 200
   - Update UI to show new email

---

### Login Flow
1. User enters: email/username, password
2. **Validation (Client)**: Both fields required, not blank
3. **API Call**: POST `/auth/login` with `{identifier: emailOrUsername, password}`
4. **Backend Checks**:
   - ✅ User exists in `users` table (email OR username match)
   - ✅ Account not disabled/deleted
   - ✅ Auth provider is 'local' (not Google-only)
   - ✅ Password matches stored hash (bcrypt verify)
5. **On Invalid Credentials (HTTP 401)**:
   - Show "Invalid email/username or password"
6. **On Account Disabled/Deleted (HTTP 403/404)**:
   - Show appropriate error
7. **On Success (HTTP 200)**:
   - Update `last_login_at` to NOW()
   - Issue new JWT tokens (accessToken + refreshToken)
   - Return user profile with all data from `users` LEFT JOIN `user_profiles`
   - Save to SessionManager
   - **Navigate to**: Main Dashboard

---

### Google Sign-In Flow
1. User taps "Sign in with Google"
2. Google returns idToken
3. **API Call**: POST `/auth/google` with `{idToken}`
4. **Backend Verification**:
   - ✅ Verify idToken with Google (OAuth2Client)
   - ✅ Extract: googleId, email, name from token
5. **Case 1: Google Account Already Linked**
   - ✅ Check `google_auth` table for this googleId
   - ✅ If found: use existing userId, update `last_login_at`
   - ✅ Issue new tokens, return user data
6. **Case 2: Email Already Registered (Different Provider)**
   - ✅ Check `users` table for this email
   - ✅ If found: reject with HTTP 409 "Account with this email already exists. Please sign in with password."
7. **Case 3: New Account**
   - ✅ Generate unique username from name (auto-suffix if taken: "john", "john1", "john2"...)
   - ✅ Create `users` row: username, email, auth_provider='google', email_verified=TRUE, account_status='active'
   - ✅ Create `user_profiles` row with display_name from Google name
   - ✅ Create `google_auth` row: googleId, googleEmail
   - ✅ Create `user_settings` row with defaults
   - ✅ Issue tokens, return user data

---

## 2. PROFILE MANAGEMENT

### View Profile
1. User taps profile card or settings button
2. **Navigate to**: SettingsActivity
3. **Load Profile Data**:
   - From SessionManager (cached from last login/register/email verification)
   - Display: username, email, display name, profile picture, date of birth, gender
   - Profile picture: if URL exists, download and cache locally; else show default avatar

### Edit Profile
1. User taps "Edit Profile" button
2. Dialog opens with fields: display name, date of birth, gender, profile picture
3. User makes changes
4. **Profile Picture Upload** (Optional):
   - User selects from gallery OR takes photo
   - Image cropped if needed
   - Saved to `context.filesDir` as "profile_image.jpg"
   - **API Call**: POST `/users/me/picture` with multipart form data
   - Backend stores to server, returns URL
   - Store URL in `user_profiles.profile_picture_url`
5. **Other Profile Fields**:
   - **API Call**: PATCH `/users/me/profile` with `{display_name, date_of_birth, gender}`
   - Backend validates date format (ISO 8601)
   - Backend updates `user_profiles` table
   - Returns updated profile
6. **On Success**:
   - Update SessionManager with new data
   - Update UI to show new profile picture & data
   - Toast: "Profile updated"
7. **On Profile Picture Error (HTTP 409)**:
   - Show toast with error message

---

### Change Username
1. User taps "Edit Profile" → "Change Username"
2. Dialog with current username field
3. User enters new username
4. **Validation**: 3-30 alphanumeric characters
5. **API Call**: PATCH `/users/me/username` with `{username: newUsername}`
6. **Backend Checks**:
   - ✅ New username NOT in `users` table (different user)
   - ✅ New username NOT in `pending_registrations`
7. **On Duplicate (HTTP 409)**:
   - Show "Username already taken"
8. **On Success**:
   - Update `users.username`
   - Update SessionManager with new username
   - Update UI to show new username

---

### Change Email
1. User taps "Change Email" in settings
2. Dialog with current email
3. User enters new email
4. **Validation**: Valid email format
5. **API Call**: PATCH `/users/me/email` with `{email: newEmail}`
6. **Backend Checks**:
   - ✅ New email NOT in `users` table (different user)
7. **On Success**:
   - Update `users.email` to new email
   - Set `users.email_verified = FALSE`
   - Set `users.account_status = 'pending_verification'`
   - Send verification code email to NEW email
   - User must verify new email before it becomes active
8. **Verification Same as Email Verification Flow** (see above)

---

### Sign Out
1. User taps "Sign Out" in settings
2. Confirm dialog: "Are you sure?"
3. **On Confirm**:
   - Clear SessionManager (remove tokens, user data)
   - Clear SharedPreferences ("is_logged_in" = false)
   - Clear local profile picture cache
   - **Navigate to**: LoginActivity
4. **All local data cleared**
5. Next login will pull fresh profile from backend

---

## 3. HEALTH READINGS COLLECTION & UPLOAD

### Device Connection (BLE)
1. User opens MainActivity
2. User taps "Scan" to find wearable device
3. User selects device from list
4. App initiates BLE connection
5. On successful connection:
   - Subscribe to BLE characteristics for: heart rate, SpO2, swelling, steps, motion, etc.
   - Decrypt incoming sensor data (AES-encrypted from wearable)
   - Display real-time values in UI: heart rate, SpO2, blood pressure, swelling, steps, motion

---

### Health Reading Upload
**When BLE Data Received**:
1. Wearable sends encrypted sensor data
2. App decrypts using AESCrypto
3. Update UI with new value
4. **SIMULTANEOUSLY**: Call `ReadingsUploadService.uploadReading(context, reading)`

**Upload Process**:
1. Extract access token from SessionManager
2. Build JSON payload with reading data (only non-null fields):
   ```json
   {
     "heart_rate": 78,
     "spo2": 98.5,
     "bp_systolic": 120,
     "bp_diastolic": 80,
     "swelling_value": "0.5",
     "step_count": 1250,
     "motion_status": "1",
     "recorded_at": "2026-05-02T14:30:00Z"
   }
   ```
3. **API Call**: POST `/readings` with Bearer token
4. **Backend**:
   - Validate JWT token
   - Extract userId from token
   - Insert into `health_readings` table:
     - reading_id (UUID)
     - user_id (from token)
     - device_id (NULL for now, could be paired device)
     - heart_rate, spo2, bp_systolic, bp_diastolic, swelling_value, step_count, motion_status
     - recorded_at (timestamp from reading OR server NOW())
     - created_at (server NOW())
   - Return HTTP 201
5. **App Logs**:
   - Success: "Reading uploaded successfully: 78bpm / 98.5%"
   - Error: Log error code and response

---

### Reading Types & Format
| Sensor | Field | Type | Range | Example |
|--------|-------|------|-------|---------|
| Heart Rate | heart_rate | Integer | 40-200 | 78 |
| SpO2 | spo2 | Double | 80-100 | 98.5 |
| BP Systolic | bp_systolic | Integer | 60-200 | 120 |
| BP Diastolic | bp_diastolic | Integer | 40-120 | 80 |
| Swelling/Edema | swelling_value | String/Numeric | 0-999 | "0.5" |
| Step Count | step_count | Integer | 0-999999 | 1250 |
| Motion | motion_status | String | "0" (static) or "1" (moving) | "1" |
| Timestamp | recorded_at | ISO 8601 UTC | UTC | "2026-05-02T14:30:00Z" |

---

### Timestamps
- **Format**: ISO 8601 with UTC timezone: `"2026-05-02T14:30:00Z"`
- **From App**: `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).timeZone = TimeZone.getTimeZone("UTC")`
- **In Database**: Stored as TIMESTAMPTZ in PostgreSQL

---

## 4. HEALTH HISTORY & ANALYTICS

### Vitals History (Local)
- App stores recent readings in memory and in `VitalsHistoryStore`
- Display on MainActivity dashboard: last 30 minutes, 1 hour, 24 hours of data
- Shows charts/graphs of heart rate, SpO2, BP trends
- Data persists in app cache, cleared on sign out

### Database History
- Backend stores ALL readings in `health_readings` table
- Query: `SELECT * FROM health_readings WHERE user_id = $1 ORDER BY recorded_at DESC`
- Index on `(user_id, recorded_at DESC)` for fast queries

---

## 5. DEVICE MANAGEMENT

### Pair Device
1. User taps "Pair Device"
2. Scan for BLE devices
3. Select device from list
4. **API Call**: POST `/devices` with `{ble_name, ble_mac, firmware_version}`
5. Backend creates row in `devices` table:
   - device_id (UUID)
   - user_id (from token)
   - ble_name, ble_mac, firmware_version
   - device_status = 'active'
   - last_connected_at = NOW()
6. Return device data
7. Save device info locally

### List Paired Devices
1. **API Call**: GET `/devices`
2. Backend queries `devices` WHERE user_id = $1 ORDER BY last_connected_at DESC
3. Return list of paired devices
4. Display in settings

### Update Device
1. User renames or updates device info
2. **API Call**: PATCH `/devices/{deviceId}` with update fields
3. Backend validates device belongs to user
4. Update `devices` row
5. Return updated device

### Remove Device
1. User taps "Unpair Device"
2. **API Call**: DELETE `/devices/{deviceId}`
3. Backend soft-deletes (sets device_status = 'removed')
4. Health readings remain but not associated with active device

---

## 6. ALERTS & THRESHOLDS

### Health Alerts
**Triggers**:
- Heart rate out of range (< 60 or > 100 BPM)
- SpO2 low (< 95%)
- Blood pressure abnormal
- Swelling detected

**On Alert**:
1. Show **red notification** in UI: "⚠️ Alert: High Heart Rate (156 BPM)"
2. Play sound/vibration
3. If enabled: show system notification
4. **Call**: `dispatchAlert(alertMessage)`
5. Store alert in `alerts` table (user_id, message, timestamp, severity)
6. **If Auto-Share Enabled**: Dispatch alert email to recipients (see Auto-Share section)

---

## 7. AUTO-SHARE / EMERGENCY CONTACTS

### Setup Auto-Share Recipients
1. User goes to Settings → "Share Health Alerts"
2. Toggle "Share Alerts" ON
3. Dialog opens: add recipient email
4. User enters recipient email, taps "Send Verification Code"
5. **App Sends Email** (via backend POST `/send-email`):
   - Subject: "SoleMate Email Verification"
   - Body includes 6-digit verification code
   - Code expires in 24 hours
   - Code sent via HttpURLConnection to backend → SendGrid
6. **Recipient Verification Flow**:
   - Recipient receives email with code
   - User enters code in app dialog
   - **App Verifies Code** (checks against temp SharedPreferences storage)
   - On success: Add email to verified recipients list
   - Persist to SharedPreferences: `auto_share_verified_emails` (JSON array)
7. Can add multiple recipients

### Alert Auto-Share
**When Alert Occurs**:
1. Check if auto-share enabled and recipients verified
2. **Dispatch Work**: Enqueue `AutoShareEmailWorker` (Android WorkManager)
3. Worker sends email to each verified recipient:
   - Subject: "SoleMate Health Alert"
   - Body: Alert message + timestamp + patient name
   - Email sent via backend
4. **Backend Logs**:
   - Store in `alerts` table with recipient info
   - Send via SendGrid

### Disable Auto-Share
1. User toggles off "Share Alerts"
2. Confirm dialog: "Recipients will no longer receive alerts"
3. **On Confirm**:
   - Set auto_share_enabled = false
   - Clear recipients list or just disable
   - **Send Email to All Recipients**: "Alert Sharing Disabled"
   - Body: "{username} has disabled alert sharing. You will no longer receive alerts."

---

## 8. SETTINGS & PREFERENCES

### User Settings (Stored in Database)
**Table**: `user_settings` (one row per user)

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| language | VARCHAR | 'en' | UI language: 'en', 'ar','fr' |
| text_size | VARCHAR | 'medium' | Font size: 'small', 'medium', 'large' |
| notifications_enabled | BOOLEAN | TRUE | System notifications for alerts |
| app_lock_enabled | BOOLEAN | FALSE | Require PIN/biometric on app open |
| voice_hints_enabled | BOOLEAN | FALSE | Read UI elements aloud (TalkBack) |
| auto_share_enabled | BOOLEAN | FALSE | Send alerts to recipients |
| theme | VARCHAR | 'light' | UI theme: 'light' or 'dark' |

### Language Selection
- User taps language picker
- Select: English, Arabic
- App stores in `user_settings.language`
- UI reloads in selected language
- Preference persists across sessions

### Text Size
- User selects: Small, Medium, Large
- Stored in `user_settings.text_size`
- UI adjusts font sizes accordingly

### Accessibility
- Voice Hints Toggle: If enabled, UI elements are read aloud via TalkBack
- Stored in `user_settings.voice_hints_enabled`

---

## 9. SECURITY & TOKENS

### JWT Tokens
- **Access Token**: 15 minutes expiration
  - Sent with every API request: `Authorization: Bearer {accessToken}`
  - Payload includes: userId, issued_at, expiry
  - Signed with JWT_SECRET
- **Refresh Token**: 30 days expiration
  - Stored in database `refresh_tokens` table
  - NOT sent in normal requests, only for token refresh
  - Can be revoked
- **Token Refresh**: When access token expires, use refresh token to get new access token
  - POST `/auth/refresh` with refresh token
  - Backend validates and issues new access token

### Password Security
- Passwords hashed with bcrypt (12 rounds: SALT_ROUNDS = 12)
- Never stored in plain text
- Compared using bcrypt.compare() during login

### Email Verification Codes
- 6-digit random code generated per signup
- Hashed with bcrypt before storage
- Expires after 10 minutes
- Max 5 resends per signup attempt
- Max 5 failed verification attempts

---

## 10. ERROR HANDLING

### Network Errors
- No internet → Show "No internet connection"
- Connection timeout → Show actual error (not generic "no wifi")
- DNS failure → Show actual error
- SSL error → Show actual error

### API Response Codes

| Code | Meaning | UI Action |
|------|---------|-----------|
| **201** | Created (signup, upload) | Success, proceed |
| **200** | Success | Proceed with response data |
| **400** | Bad request (expired code, wrong format) | Show error message from response |
| **401** | Unauthorized (invalid credentials, expired token) | Show error or redirect to login |
| **403** | Forbidden (account disabled, wrong auth provider) | Show error message |
| **404** | Not found (user, pending registration) | Show error |
| **409** | Conflict (duplicate email/username) | Show specific error |
| **429** | Too many requests (too many resends/attempts) | Show cooldown timer |
| **500** | Server error | Show "Server error, try again later" |
| **-1** | Network error (no connection) | Show actual exception message |

---

## 11. DATA SYNC ACROSS DEVICES

### Profile Sync
- **When Signing In**: App fetches fresh profile from backend via `/auth/login` or `/auth/google`
- **Data Synced**: username, email, displayName, profilePictureUrl, dateOfBirth, gender
- **From**: `users` LEFT JOIN `user_profiles` table
- **Stored Locally**: SessionManager SharedPreferences
- **On New Phone**: Same account login → fresh profile data displayed

### Health Readings Sync
- **Upload Automatic**: Every reading from wearable uploaded immediately (or on best-effort)
- **Query from Backend**: Not currently implemented in app, but possible via GET `/readings`
- **Backend Stores**: All readings in `health_readings` table forever
- **On New Phone**: Historical readings NOT automatically synced to new device (local history cleared on sign out)

### Device Pairing Sync
- **Via API**: GET `/devices` returns user's paired devices
- **Not Auto-Synced**: App stores locally, must manually refresh or re-pair on new phone

---

## 12. DATABASE TABLES SUMMARY

| Table | Rows | Purpose |
|-------|------|---------|
| `users` | 1 per verified account | Account credentials & status |
| `user_profiles` | 1 per user | Display name, picture, DOB, gender |
| `user_settings` | 1 per user | Language, theme, preferences |
| `pending_registrations` | 0-N | Temporary during signup (deleted after email verify) |
| `google_auth` | 0-1 per user | Google account linking |
| `refresh_tokens` | 0-N per user | Valid refresh tokens (revokable) |
| `password_reset_tokens` | 0-N | Temporary for password reset |
| `devices` | 0-N per user | Paired BLE wearables |
| `health_readings` | 0-N | Vitals from wearable (HR, SPO2, BP, steps) |
| `pressure_matrix_readings` | 0-N | Raw pressure sensor data |
| `gait_analytics` | 0-N | Computed gait metrics |
| `alerts` | 0-N | Health alerts triggered |
| `auto_share_recipients` | 0-N | Recipients for alert emails |
| `feedback` | 0-N | User feedback/bug reports |
| `email_verification_codes` | DEPRECATED | (replaced by code_hash in pending_registrations) |

---

## 13. CURRENT KNOWN ISSUES TO FIX

✅ **FIXED**:
- Database schema mismatch (pending_registrations not created)
- Email verification UI centering
- Health readings upload not wired to MainActivity

⚠️ **NEXT TO VERIFY**:
- [ ] Test complete signup flow end-to-end
- [ ] Verify health readings appear in `health_readings` table
- [ ] Test profile sync on new device
- [ ] Test auto-share email dispatch
- [ ] Verify all error messages display correctly (not generic "no wifi")

---

## 14. TESTING CHECKLIST

### Signup & Email Verification
- [ ] Sign up with new email → get verification code email
- [ ] Enter correct code → success, redirected to dashboard
- [ ] Enter wrong code → error after 5 attempts
- [ ] Code expires after 10 min
- [ ] Resend code works with 60s cooldown
- [ ] Change email before verification
- [ ] Cannot sign up with duplicate email → error 409
- [ ] Cannot sign up with duplicate username → error 409

### Login
- [ ] Login with email + password → success
- [ ] Login with username + password → success
- [ ] Wrong password → error 401
- [ ] Non-existent email → error 401

### Google Sign-In
- [ ] Sign in with Google (new account) → account created
- [ ] Sign in with Google (existing) → account retrieved
- [ ] Email conflict (Google email already in use) → error 409

### Profile
- [ ] View profile shows all data from database
- [ ] Change username → update visible, unique validation works
- [ ] Change display name → update visible
- [ ] Change profile picture → uploaded and displayed
- [ ] Sign out → all data cleared locally
- [ ] Sign back in on new device → same profile synced

### Health Readings
- [ ] Connect to BLE device
- [ ] Receive heart rate → displayed AND uploaded to `/readings`
- [ ] Receive SpO2 → displayed AND uploaded
- [ ] Receive BP → displayed AND uploaded
- [ ] Receive steps → displayed AND uploaded
- [ ] Readings appear in `health_readings` table with correct user_id

### Alerts
- [ ] Heart rate out of range → alert shown
- [ ] SpO2 low → alert shown
- [ ] Alert notification displays

### Auto-Share
- [ ] Add recipient email → verification code sent
- [ ] Verify recipient → stored in verified list
- [ ] Enable auto-share → ON
- [ ] Trigger alert → email sent to recipient
- [ ] Disable auto-share → email sent to notify recipient

---

## 15. QUICK REFERENCE: What Goes In Database

### On Signup
```
pending_registrations:
  id: UUID (generated)
  username: "john_doe"
  email: "john@example.com"
  password_hash: bcrypt("SecurePass@123")
  code_hash: bcrypt("123456")
  code_expires_at: NOW() + 10 min
  resend_count: 0
  attempts: 0
  created_at: NOW()
```

### On Email Verify
```
DELETE FROM pending_registrations WHERE id = {id}

INSERT INTO users:
  user_id: UUID (generated)
  username: "john_doe"
  email: "john@example.com"
  password_hash: bcrypt("SecurePass@123")
  auth_provider: "local"
  email_verified: TRUE
  account_status: "active"
  created_at: NOW()

INSERT INTO user_profiles:
  user_id: {above UUID}
  display_name: NULL
  profile_picture_url: NULL
  date_of_birth: NULL
  gender: NULL

INSERT INTO user_settings:
  user_id: {above UUID}
  language: "en"
  text_size: "medium"
  notifications_enabled: TRUE
  app_lock_enabled: FALSE
  voice_hints_enabled: FALSE
  auto_share_enabled: FALSE
  theme: "light"
```

### On Health Reading
```
INSERT INTO health_readings:
  reading_id: UUID
  user_id: {from JWT token}
  device_id: NULL or {paired device}
  heart_rate: 78.0
  spo2: 98.5
  bp_systolic: 120.0
  bp_diastolic: 80.0
  swelling_value: 0.5
  step_count: 1250
  motion_status: "1"
  recorded_at: {from app or NOW()}
  created_at: NOW()
```

### On Profile Update
```
UPDATE user_profiles SET:
  display_name: "John Doe"
  profile_picture_url: "/uploads/pic123.jpg"
  date_of_birth: "1990-05-15"
  gender: "M"
  updated_at: NOW()
WHERE user_id = {userId}
```

---

**ALL EXPECTED BEHAVIOR DEFINED ABOVE.**

**Test against these specifications to verify app is working correctly.**

