const Joi = require('joi');

exports.createRecipientSchema = Joi.object({
  recipient_name:  Joi.string().max(80).required(),
  recipient_email: Joi.string().email().required(),
  alerts_enabled:  Joi.boolean().default(true),
});

exports.updateRecipientSchema = Joi.object({
  recipient_name:  Joi.string().max(80),
  alerts_enabled:  Joi.boolean(),
});
