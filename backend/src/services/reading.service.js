const readingModel = require('../models/reading.model');
const deviceModel = require('../models/device.model');

function appError(msg, status) {
  return Object.assign(new Error(msg), { status });
}

exports.create = async (userId, data) => {
  if (data.device_id) {
    const device = await deviceModel.findByIdAndUser(data.device_id, userId);
    if (!device.rows.length || device.rows[0].device_status === 'removed') {
      throw appError('Device not found.', 404);
    }
  }
  const { rows } = await readingModel.create(userId, data);
  return rows[0];
};

exports.latest = async (userId) => {
  const { rows } = await readingModel.latest(userId);
  return rows[0] || null;
};

exports.history = async (userId, from, to, limit) => {
  const { rows } = await readingModel.history(userId, from, to, limit);
  return rows;
};
