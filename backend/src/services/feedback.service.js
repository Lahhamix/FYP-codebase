const model = require('../models/feedback.model');

exports.create  = async (userId, { message, category }) =>
  (await model.create(userId, message, category)).rows[0];

exports.listMy  = async (userId) => (await model.listByUser(userId)).rows;
