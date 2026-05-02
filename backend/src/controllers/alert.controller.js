const svc  = require('../services/alert.service');
const wrap = fn => async (req, res, next) => {
  try { await fn(req, res); } catch (e) { next(e); }
};

exports.create     = wrap(async (req, res) => res.status(201).json(await svc.create(req.user.userId, req.body)));
exports.list       = wrap(async (req, res) => res.json(await svc.list(req.user.userId)));
exports.updateSent = wrap(async (req, res) => res.json(await svc.updateSent(req.params.id, req.user.userId, req.body.sent)));
