const model = require('../models/pressureMatrix.model');

exports.create  = async (userId, data) => (await model.create(userId, data)).rows[0];
exports.latest  = async (userId)       => (await model.latest(userId)).rows[0] || null;
exports.history = async (userId, from, to, limit) => (await model.history(userId, from, to, limit)).rows;
