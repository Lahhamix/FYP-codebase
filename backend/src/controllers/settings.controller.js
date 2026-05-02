const svc  = require('../services/settings.service');
const wrap = fn => async (req, res, next) => {
  try { await fn(req, res); } catch (e) { next(e); }
};

exports.get    = wrap(async (req, res) => res.json(await svc.get(req.user.userId)));
exports.update = wrap(async (req, res) => res.json(await svc.update(req.user.userId, req.body)));
