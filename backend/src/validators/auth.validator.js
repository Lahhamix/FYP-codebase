const Joi = require('joi');

const password = Joi.string()
  .min(8)
  .pattern(/[A-Z]/, 'uppercase letter')
  .pattern(/[a-z]/, 'lowercase letter')
  .pattern(/[0-9]/, 'number')
  .pattern(/[^A-Za-z0-9]/, 'special character')
  .required()
  .messages({
    'string.min':          'Password must be at least 8 characters.',
    'string.pattern.name': 'Password must contain at least one {#name}.',
  });

exports.registerSchema = Joi.object({
  username: Joi.string().alphanum().min(3).max(30).required(),
  email:    Joi.string().email().lowercase().required(),
  password,
});

exports.loginSchema = Joi.object({
  identifier: Joi.string().required(),
  password:   Joi.string().required(),
});

exports.verifyEmailSchema = Joi.object({
  userId: Joi.string().uuid().required(),
  code:   Joi.string().length(6).pattern(/^\d+$/).required(),
});

exports.resendSchema = Joi.object({
  userId: Joi.string().uuid().required(),
});

exports.changePendingEmailSchema = Joi.object({
  userId: Joi.string().uuid().required(),
  email:  Joi.string().email().lowercase().required(),
});

exports.forgotPasswordSchema = Joi.object({
  identifier: Joi.string().required(),
});

exports.resetPasswordSchema = Joi.object({
  token:    Joi.string().required(),
  password,
});

exports.refreshSchema = Joi.object({
  refreshToken: Joi.string().required(),
});

exports.googleSchema = Joi.object({
  idToken: Joi.string().required(),
});
