const readingModel = require('../models/reading.model');

exports.create = async (userId, data) => {
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
