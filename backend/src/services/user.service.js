const bcrypt    = require('bcrypt');
const userModel = require('../models/user.model');

function appError(msg, status) {
  return Object.assign(new Error(msg), { status });
}

exports.getMe = async (userId) => {
  const [userRes, profileRes] = await Promise.all([
    userModel.findById(userId),
    userModel.getProfile(userId),
  ]);
  if (!userRes.rows.length) throw appError('User not found.', 404);
  return { ...userRes.rows[0], profile: profileRes.rows[0] || null };
};

exports.updateProfile = async (userId, fields) => {
  const { rows } = await userModel.updateProfile(userId, fields);
  if (!rows.length) throw appError('Profile not found.', 404);
  return rows[0];
};

exports.changeUsername = async (userId, newUsername) => {
  const check = await userModel.findByUsername(newUsername);
  if (check.rows.length && check.rows[0].user_id !== userId) {
    throw appError('Username already taken.', 409);
  }
  await userModel.updateUsername(userId, newUsername);
};

exports.changeEmail = async (userId, newEmail) => {
  const check = await userModel.findByEmail(newEmail);
  if (check.rows.length && check.rows[0].user_id !== userId) {
    throw appError('Email already registered.', 409);
  }
  await userModel.updateEmail(userId, newEmail);
};

exports.updateProfilePicture = async (userId, url) => {
  await userModel.updateProfilePicture(userId, url);
  return url;
};
