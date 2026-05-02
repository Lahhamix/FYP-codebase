const Joi = require('joi');

exports.createGaitSchema = Joi.object({
  device_id:           Joi.string().uuid().allow(null),
  deviation_score:     Joi.number().allow(null),
  big_toe_pressure:    Joi.object().allow(null),
  plantar_pressure:    Joi.object().allow(null),
  ankle_cuff_metrics:  Joi.object().allow(null),
  step_symmetry:       Joi.number().allow(null),
  risk_flag:           Joi.boolean().default(false),
  recorded_at:         Joi.string().isoDate().allow(null),
});

exports.historyQuerySchema = Joi.object({
  from:  Joi.string().isoDate().required(),
  to:    Joi.string().isoDate().required(),
  limit: Joi.number().integer().min(1).max(500).default(100),
});
