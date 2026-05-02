const db = require('../config/db');

exports.create = (userId, data) =>
  db.query(
    `INSERT INTO pressure_matrix_readings
       (user_id, device_id, matrix_values, pressure_zones, foot_side, recorded_at)
     VALUES ($1,$2,$3,$4,$5, COALESCE($6::timestamptz, NOW()))
     RETURNING *`,
    [
      userId,
      data.device_id || null,
      JSON.stringify(data.matrix_values),
      data.pressure_zones ? JSON.stringify(data.pressure_zones) : null,
      data.foot_side || null,
      data.recorded_at || null,
    ]
  );

exports.latest = (userId) =>
  db.query(
    `SELECT * FROM pressure_matrix_readings
     WHERE user_id = $1 ORDER BY recorded_at DESC LIMIT 1`,
    [userId]
  );

exports.history = (userId, from, to, limit) =>
  db.query(
    `SELECT * FROM pressure_matrix_readings
     WHERE user_id = $1 AND recorded_at BETWEEN $2 AND $3
     ORDER BY recorded_at DESC LIMIT $4`,
    [userId, from, to, limit]
  );
