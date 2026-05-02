const svc = require('../services/auth.service');

const wrap = fn => async (req, res, next) => {
  try { await fn(req, res); } catch (e) { next(e); }
};

exports.register = wrap(async (req, res) => {
  const userId = await svc.register(req.body);
  res.status(201).json({ userId });
});

exports.verifyEmail = wrap(async (req, res) => {
  const tokens = await svc.verifyEmail(req.body.userId, req.body.code);
  res.json(tokens);
});

exports.resendVerification = wrap(async (req, res) => {
  await svc.sendVerificationCode(req.body.userId);
  res.json({ message: 'Verification code resent.' });
});

exports.changePendingEmail = wrap(async (req, res) => {
  await svc.changePendingEmail(req.body.userId, req.body.email);
  res.json({ message: 'Verification code sent to new email.' });
});

exports.login = wrap(async (req, res) => {
  const tokens = await svc.login(req.body.identifier, req.body.password);
  res.json(tokens);
});

exports.googleSignIn = wrap(async (req, res) => {
  const tokens = await svc.googleSignIn(req.body.idToken);
  res.json(tokens);
});

exports.forgotPassword = wrap(async (req, res) => {
  await svc.forgotPassword(req.body.identifier);
  res.json({ message: 'If that account exists, a reset code has been sent.' });
});

exports.resetPassword = wrap(async (req, res) => {
  await svc.resetPassword(req.body.token, req.body.password);
  res.json({ message: 'Password updated successfully.' });
});

exports.refresh = wrap(async (req, res) => {
  const tokens = await svc.refreshTokens(req.body.refreshToken);
  res.json(tokens);
});

exports.logout = wrap(async (req, res) => {
  await svc.logout(req.body.refreshToken);
  res.json({ message: 'Logged out.' });
});
