const alertModel = require('../models/alert.model');

function appError(msg, status) {
  return Object.assign(new Error(msg), { status });
}

exports.create = async (userId, data) => (await alertModel.create(userId, data)).rows[0];

exports.list = async (userId) => (await alertModel.list(userId)).rows;

exports.updateSent = async (alertId, userId, sent) => {
  const { rows } = await alertModel.updateSent(alertId, userId, sent);
  if (!rows.length) throw appError('Alert not found.', 404);
  return rows[0];
};
