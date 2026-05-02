const Joi = require('joi');

exports.createFeedbackSchema = Joi.object({
  message:  Joi.string().min(5).max(2000).required(),
  category: Joi.string().max(50).allow('', null),
});
