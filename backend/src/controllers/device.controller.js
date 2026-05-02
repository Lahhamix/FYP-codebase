const svc  = require('../services/device.service');
const wrap = fn => async (req, res, next) => {
  try { await fn(req, res); } catch (e) { next(e); }
};

exports.list   = wrap(async (req, res) => res.json(await svc.list(req.user.userId)));
exports.create = wrap(async (req, res) => res.status(201).json(await svc.create(req.user.userId, req.body)));
exports.update = wrap(async (req, res) => res.json(await svc.update(req.params.id, req.user.userId, req.body)));
exports.remove = wrap(async (req, res) => { await svc.remove(req.params.id, req.user.userId); res.status(204).end(); });
