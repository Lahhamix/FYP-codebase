const Joi = require('joi');

exports.createReadingSchema = Joi.object({
  device_id:     Joi.string().uuid().allow(null),
  heart_rate:    Joi.number().integer().min(0).max(300).allow(null),
  spo2:          Joi.number().integer().min(0).max(100).allow(null),
  bp_systolic:   Joi.number().integer().min(0).max(300).allow(null),
  bp_diastolic:  Joi.number().integer().min(0).max(200).allow(null),
  swelling_value:Joi.number().min(0).allow(null),
  step_count:    Joi.number().integer().min(0).allow(null),
  motion_status: Joi.string().max(50).allow('', null),
  recorded_at:   Joi.string().isoDate().allow(null),
});

exports.historyQuerySchema = Joi.object({
  from:  Joi.string().isoDate().required(),
  to:    Joi.string().isoDate().required(),
  limit: Joi.number().integer().min(1).max(1000).default(200),
});
