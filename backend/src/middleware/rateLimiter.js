const rateLimit = require('express-rate-limit');

const make = (windowMin, max, message) =>
  rateLimit({
    skip:            () => process.env.NODE_ENV === 'test',
    windowMs:       windowMin * 60 * 1000,
    max,
    message:        { error: message },
    standardHeaders: true,
    legacyHeaders:  false,
  });

exports.authLimiter   = make(15, 10, 'Too many attempts. Try again in 15 minutes.');
exports.verifyLimiter = make(60, 20, 'Too many verification attempts. Try again in 1 hour.');
exports.resetLimiter  = make(60, 5,  'Too many password reset requests. Try again in 1 hour.');
exports.uploadLimiter = make(60, 10,  'Too many upload requests.');
