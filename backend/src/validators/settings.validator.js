const Joi = require('joi');

exports.updateSettingsSchema = Joi.object({
  language:              Joi.string().max(10),
  text_size:             Joi.string().valid('standard', 'large', 'extra_large'),
  notifications_enabled: Joi.boolean(),
  app_lock_enabled:      Joi.boolean(),
  voice_hints_enabled:   Joi.boolean(),
  auto_share_enabled:    Joi.boolean(),
  theme:                 Joi.string().valid('light', 'dark'),
});
