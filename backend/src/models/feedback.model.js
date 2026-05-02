const db = require('../config/db');

exports.create = (userId, message, category) =>
  db.query(
    'INSERT INTO feedback (user_id, message, category) VALUES ($1,$2,$3) RETURNING *',
    [userId || null, message, category || null]
  );

exports.listByUser = (userId) =>
  db.query(
    'SELECT * FROM feedback WHERE user_id = $1 ORDER BY created_at DESC',
    [userId]
  );
