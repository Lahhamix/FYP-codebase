const Joi = require('joi');

exports.updateProfileSchema = Joi.object({
  display_name:  Joi.string().max(80).allow('', null),
  date_of_birth: Joi.string().isoDate().allow(null),
  gender:        Joi.string().max(30).allow('', null),
}).min(1);

exports.changeUsernameSchema = Joi.object({
  username: Joi.string().alphanum().min(3).max(30).required(),
});

exports.changeEmailSchema = Joi.object({
  email: Joi.string().email().lowercase().required(),
});
