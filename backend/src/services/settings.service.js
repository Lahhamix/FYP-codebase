const settingsModel = require('../models/settings.model');

exports.get = async (userId) => {
  const { rows } = await settingsModel.get(userId);
  if (!rows.length) {
    const created = await settingsModel.create(userId);
    return created.rows[0];
  }
  return rows[0];
};

exports.update = async (userId, fields) => {
  const { rows } = await settingsModel.update(userId, fields);
  if (!rows.length) throw Object.assign(new Error('Settings not found.'), { status: 404 });
  return rows[0];
};
