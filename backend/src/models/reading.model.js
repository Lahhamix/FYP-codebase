const db = require('../config/db');

exports.create = (userId, data) =>
  db.query(
    `INSERT INTO health_readings
       (user_id, device_id, heart_rate, spo2, bp_systolic, bp_diastolic,
        swelling_value, step_count, motion_status, recorded_at)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9, COALESCE($10::timestamptz, NOW()))
     RETURNING *`,
    [
      userId,
      data.device_id ?? null,
      data.heart_rate ?? null,
      data.spo2 ?? null,
      data.bp_systolic ?? null,
      data.bp_diastolic ?? null,
      data.swelling_value == null ? null : String(data.swelling_value),
      data.step_count ?? null,
      data.motion_status ?? null,
      data.recorded_at ?? null,
    ]
  );

exports.findByIdAndUser = (readingId, userId) =>
  db.query(
    'SELECT * FROM health_readings WHERE reading_id = $1 AND user_id = $2 LIMIT 1',
    [readingId, userId]
  );

exports.latest = (userId) =>
  db.query(
    'SELECT * FROM health_readings WHERE user_id = $1 ORDER BY recorded_at DESC LIMIT 1',
    [userId]
  );

exports.history = (userId, from, to, limit) =>
  db.query(
    `SELECT * FROM health_readings
     WHERE user_id = $1 AND recorded_at BETWEEN $2 AND $3
     ORDER BY recorded_at DESC LIMIT $4`,
    [userId, from, to, limit]
  );
