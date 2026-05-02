const Joi = require('joi');

exports.createMatrixSchema = Joi.object({
  device_id:      Joi.string().uuid().allow(null),
  matrix_values:  Joi.array().items(Joi.number()).min(1).required(),
  pressure_zones: Joi.object().allow(null),
  foot_side:      Joi.string().valid('left', 'right', 'both').allow(null),
  recorded_at:    Joi.string().isoDate().allow(null),
});

exports.historyQuerySchema = Joi.object({
  from:  Joi.string().isoDate().required(),
  to:    Joi.string().isoDate().required(),
  limit: Joi.number().integer().min(1).max(500).default(100),
});
