const db = require('../config/db');

exports.list = (userId) =>
  db.query(
    `SELECT * FROM devices WHERE user_id = $1 AND device_status != 'removed'
     ORDER BY last_connected_at DESC NULLS LAST`,
    [userId]
  );

exports.findByIdAndUser = (deviceId, userId) =>
  db.query(
    'SELECT * FROM devices WHERE device_id = $1 AND user_id = $2',
    [deviceId, userId]
  );

exports.create = (userId, bleName, bleMac, firmwareVersion) =>
  db.query(
    `INSERT INTO devices (user_id, ble_name, ble_mac, firmware_version)
     VALUES ($1, $2, $3, $4) RETURNING *`,
    [userId, bleName, bleMac || null, firmwareVersion || null]
  );

exports.update = (deviceId, userId, fields) => {
  const keys   = Object.keys(fields);
  const values = Object.values(fields);
  const sets   = keys.map((k, i) => `${k} = $${i + 3}`).join(', ');
  return db.query(
    `UPDATE devices SET ${sets}, updated_at = NOW()
     WHERE device_id = $1 AND user_id = $2 RETURNING *`,
    [deviceId, userId, ...values]
  );
};

exports.remove = (deviceId, userId) =>
  db.query(
    `UPDATE devices SET device_status = 'removed', updated_at = NOW()
     WHERE device_id = $1 AND user_id = $2`,
    [deviceId, userId]
  );
