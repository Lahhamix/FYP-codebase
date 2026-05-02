const deviceModel = require('../models/device.model');

function appError(msg, status) {
  return Object.assign(new Error(msg), { status });
}

exports.list = (userId) => deviceModel.list(userId).then(r => r.rows);

exports.create = async (userId, { ble_name, ble_mac, firmware_version }) => {
  const { rows } = await deviceModel.create(userId, ble_name, ble_mac, firmware_version);
  return rows[0];
};

exports.update = async (deviceId, userId, fields) => {
  const exists = await deviceModel.findByIdAndUser(deviceId, userId);
  if (!exists.rows.length) throw appError('Device not found.', 404);
  const { rows } = await deviceModel.update(deviceId, userId, fields);
  return rows[0];
};

exports.remove = async (deviceId, userId) => {
  const exists = await deviceModel.findByIdAndUser(deviceId, userId);
  if (!exists.rows.length) throw appError('Device not found.', 404);
  await deviceModel.remove(deviceId, userId);
};
