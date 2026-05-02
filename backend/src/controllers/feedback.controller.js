const svc  = require('../services/feedback.service');
const wrap = fn => async (req, res, next) => {
  try { await fn(req, res); } catch (e) { next(e); }
};

exports.create = wrap(async (req, res) => res.status(201).json(await svc.create(req.user.userId, req.body)));
exports.listMy = wrap(async (req, res) => res.json(await svc.listMy(req.user.userId)));
