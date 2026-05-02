const Joi = require('joi');

exports.updateProfileSchema = Joi.object({
  display_name:            Joi.string().max(80).allow('', null),
  date_of_birth:           Joi.string().isoDate().allow(null),
  gender:                  Joi.string().max(30).allow('', null),
  emergency_contact_name:  Joi.string().max(80).allow('', null),
  emergency_contact_phone: Joi.string().max(30).allow('', null),
});

exports.changeUsernameSchema = Joi.object({
  username: Joi.string().alphanum().min(3).max(30).required(),
});

exports.changeEmailSchema = Joi.object({
  email: Joi.string().email().required(),
});
