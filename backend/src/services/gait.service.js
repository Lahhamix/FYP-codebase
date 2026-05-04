const model = require('../models/gait.model');
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
  return (await model.create(userId, data)).rows[0];
};
exports.latest  = async (userId)       => (await model.latest(userId)).rows[0] || null;
exports.history = async (userId, from, to, limit) => (await model.history(userId, from, to, limit)).rows;
