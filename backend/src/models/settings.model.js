const db = require('../config/db');

exports.get = (userId) =>
  db.query('SELECT * FROM user_settings WHERE user_id = $1', [userId]);

exports.create = (userId) =>
  db.query('INSERT INTO user_settings (user_id) VALUES ($1) RETURNING *', [userId]);

exports.update = (userId, fields) => {
  const keys   = Object.keys(fields);
  if (!keys.length) return exports.get(userId);
  const values = Object.values(fields);
  const cols = keys.join(', ');
  const valuePlaceholders = keys.map((_, i) => `$${i + 2}`).join(', ');
  const sets = keys.map((k) => `${k} = EXCLUDED.${k}`).join(', ');
  return db.query(
    `INSERT INTO user_settings (user_id, ${cols}, updated_at)
     VALUES ($1, ${valuePlaceholders}, NOW())
     ON CONFLICT (user_id) DO UPDATE SET ${sets}, updated_at = NOW()
     RETURNING *`,
    [userId, ...values]
  );
};
