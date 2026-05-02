const jwt = require('jsonwebtoken');
const env  = require('./env');

function signAccess(userId) {
  return jwt.sign({ sub: userId }, env.JWT_SECRET, { expiresIn: env.JWT_EXPIRES_IN });
}

function verifyAccess(token) {
  return jwt.verify(token, env.JWT_SECRET);
}

function signRefresh(userId) {
  return jwt.sign({ sub: userId }, env.REFRESH_SECRET, { expiresIn: env.REFRESH_EXPIRES_IN });
}

function verifyRefresh(token) {
  return jwt.verify(token, env.REFRESH_SECRET);
}

module.exports = { signAccess, verifyAccess, signRefresh, verifyRefresh };
