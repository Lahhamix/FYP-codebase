const svc  = require('../services/gait.service');
const { historyQuerySchema } = require('../validators/gait.validator');
const wrap = fn => async (req, res, next) => {
  try { await fn(req, res); } catch (e) { next(e); }
};

exports.create  = wrap(async (req, res) => res.status(201).json(await svc.create(req.user.userId, req.body)));
exports.latest  = wrap(async (req, res) => res.json(await svc.latest(req.user.userId)));
exports.history = wrap(async (req, res) => {
  const { error, value } = historyQuerySchema.validate(req.query);
  if (error) return res.status(400).json({ error: error.details[0].message });
  res.json(await svc.history(req.user.userId, value.from, value.to, value.limit));
});
