const db = require('../config/db');

exports.list = (userId) =>
  db.query(
    'SELECT * FROM auto_share_recipients WHERE user_id = $1 ORDER BY created_at ASC',
    [userId]
  );

exports.findByIdAndUser = (recipientId, userId) =>
  db.query(
    'SELECT * FROM auto_share_recipients WHERE recipient_id = $1 AND user_id = $2',
    [recipientId, userId]
  );

exports.create = (userId, name, email, alertsEnabled) =>
  db.query(
    `INSERT INTO auto_share_recipients (user_id, recipient_name, recipient_email, alerts_enabled)
     VALUES ($1,$2,$3,$4) RETURNING *`,
    [userId, name, email, alertsEnabled ?? true]
  );

exports.update = (recipientId, userId, fields) => {
  const keys   = Object.keys(fields);
  if (!keys.length) return exports.findByIdAndUser(recipientId, userId);
  const values = Object.values(fields);
  const sets   = keys.map((k, i) => `${k} = $${i + 3}`).join(', ');
  return db.query(
    `UPDATE auto_share_recipients SET ${sets}, updated_at = NOW()
     WHERE recipient_id = $1 AND user_id = $2 RETURNING *`,
    [recipientId, userId, ...values]
  );
};

exports.remove = (recipientId, userId) =>
  db.query(
    'DELETE FROM auto_share_recipients WHERE recipient_id = $1 AND user_id = $2',
    [recipientId, userId]
  );
