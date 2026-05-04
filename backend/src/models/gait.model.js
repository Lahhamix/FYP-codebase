const db = require('../config/db');

exports.create = (userId, data) =>
  db.query(
    `INSERT INTO gait_analytics
       (user_id, device_id, deviation_score, big_toe_pressure, plantar_pressure,
        ankle_cuff_metrics, step_symmetry, risk_flag, recorded_at)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8, COALESCE($9::timestamptz, NOW()))
     RETURNING *`,
    [
      userId,
      data.device_id ?? null,
      data.deviation_score ?? null,
      data.big_toe_pressure ? JSON.stringify(data.big_toe_pressure) : null,
      data.plantar_pressure ? JSON.stringify(data.plantar_pressure) : null,
      data.ankle_cuff_metrics ? JSON.stringify(data.ankle_cuff_metrics) : null,
      data.step_symmetry ?? null,
      data.risk_flag ?? false,
      data.recorded_at ?? null,
    ]
  );

exports.latest = (userId) =>
  db.query(
    'SELECT * FROM gait_analytics WHERE user_id = $1 ORDER BY recorded_at DESC LIMIT 1',
    [userId]
  );

exports.history = (userId, from, to, limit) =>
  db.query(
    `SELECT * FROM gait_analytics
     WHERE user_id = $1 AND recorded_at BETWEEN $2 AND $3
     ORDER BY recorded_at DESC LIMIT $4`,
    [userId, from, to, limit]
  );
