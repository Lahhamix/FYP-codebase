const router = require('express').Router();
const ctrl   = require('../controllers/auth.controller');
const auth   = require('../middleware/authenticate');
const validate = require('../middleware/validate');
const { authLimiter, verifyLimiter, resetLimiter } = require('../middleware/rateLimiter');
const v      = require('../validators/auth.validator');

router.post('/register',            authLimiter,   validate(v.registerSchema),       ctrl.register);
router.post('/login',               authLimiter,   validate(v.loginSchema),           ctrl.login);
router.post('/google',              authLimiter,   validate(v.googleSchema),          ctrl.googleSignIn);
router.post('/verify-email',          verifyLimiter, validate(v.verifyEmailSchema),       ctrl.verifyEmail);
router.post('/resend-verification',  verifyLimiter, validate(v.resendSchema),              ctrl.resendVerification);
router.post('/change-pending-email', authLimiter,   validate(v.changePendingEmailSchema),  ctrl.changePendingEmail);
router.post('/forgot-password',     resetLimiter,  validate(v.forgotPasswordSchema),  ctrl.forgotPassword);
router.post('/reset-password',      resetLimiter,  validate(v.resetPasswordSchema),   ctrl.resetPassword);
router.post('/refresh',             authLimiter,   validate(v.refreshSchema),         ctrl.refresh);
router.post('/logout',              auth,                                              ctrl.logout);

module.exports = router;
