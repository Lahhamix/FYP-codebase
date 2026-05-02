const db = require('../config/db');

exports.get = (userId) =>
  db.query('SELECT * FROM user_settings WHERE user_id = $1', [userId]);

exports.create = (userId) =>
  db.query('INSERT INTO user_settings (user_id) VALUES ($1) RETURNING *', [userId]);

exports.update = (userId, fields) => {
  const keys   = Object.keys(fields);
  const values = Object.values(fields);
  const sets   = keys.map((k, i) => `${k} = $${i + 2}`).join(', ');
  return db.query(
    `UPDATE user_settings SET ${sets}, updated_at = NOW()
     WHERE user_id = $1 RETURNING *`,
    [userId, ...values]
  );
};
