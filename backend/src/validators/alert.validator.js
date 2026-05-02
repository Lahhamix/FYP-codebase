const Joi = require('joi');

exports.createAlertSchema = Joi.object({
  alert_type: Joi.string().max(80).required(),
  severity:   Joi.string().valid('info', 'warning', 'critical').default('info'),
  message:    Joi.string().max(500).required(),
  reading_id: Joi.string().uuid().allow(null),
});

exports.updateAlertSchema = Joi.object({
  sent: Joi.boolean().required(),
});
