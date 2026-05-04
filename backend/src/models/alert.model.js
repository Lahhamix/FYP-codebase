const db = require('../config/db');

exports.create = (userId, data) =>
  db.query(
    `INSERT INTO alerts (user_id, alert_type, severity, message, reading_id)
     VALUES ($1,$2,$3,$4,$5) RETURNING *`,
    [userId, data.alert_type, data.severity ?? 'info', data.message, data.reading_id ?? null]
  );

exports.list = (userId, limit = 50) =>
  db.query(
    'SELECT * FROM alerts WHERE user_id = $1 ORDER BY created_at DESC LIMIT $2',
    [userId, limit]
  );

exports.updateSent = (alertId, userId, sent) =>
  db.query(
    'UPDATE alerts SET sent = $1 WHERE alert_id = $2 AND user_id = $3 RETURNING *',
    [sent, alertId, userId]
  );
