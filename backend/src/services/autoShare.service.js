const model = require('../models/autoShare.model');

function appError(msg, status) {
  return Object.assign(new Error(msg), { status });
}

exports.list = async (userId) => (await model.list(userId)).rows;

exports.create = async (userId, { recipient_name, recipient_email, alerts_enabled }) => {
  const { rows } = await model.create(userId, recipient_name, recipient_email, alerts_enabled);
  return rows[0];
};

exports.update = async (recipientId, userId, fields) => {
  const exists = await model.findByIdAndUser(recipientId, userId);
  if (!exists.rows.length) throw appError('Recipient not found.', 404);
  const { rows } = await model.update(recipientId, userId, fields);
  return rows[0];
};

exports.remove = async (recipientId, userId) => {
  const exists = await model.findByIdAndUser(recipientId, userId);
  if (!exists.rows.length) throw appError('Recipient not found.', 404);
  await model.remove(recipientId, userId);
};
