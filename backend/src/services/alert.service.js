const alertModel = require('../models/alert.model');
const readingModel = require('../models/reading.model');

function appError(msg, status) {
  return Object.assign(new Error(msg), { status });
}

exports.create = async (userId, data) => {
  if (data.reading_id) {
    const reading = await readingModel.findByIdAndUser(data.reading_id, userId);
    if (!reading.rows.length) throw appError('Reading not found.', 404);
  }
  return (await alertModel.create(userId, data)).rows[0];
};

exports.list = async (userId) => (await alertModel.list(userId)).rows;

exports.updateSent = async (alertId, userId, sent) => {
  const { rows } = await alertModel.updateSent(alertId, userId, sent);
  if (!rows.length) throw appError('Alert not found.', 404);
  return rows[0];
};
