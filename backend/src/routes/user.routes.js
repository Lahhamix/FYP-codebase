const router   = require('express').Router();
const ctrl     = require('../controllers/user.controller');
const auth     = require('../middleware/authenticate');
const validate = require('../middleware/validate');
const { uploadLimiter } = require('../middleware/rateLimiter');
const v        = require('../validators/user.validator');

router.get ('/me',                 auth,                                     ctrl.getMe);
router.patch('/me',                auth, validate(v.updateProfileSchema),    ctrl.updateProfile);
router.patch('/me/username',       auth, validate(v.changeUsernameSchema),   ctrl.changeUsername);
router.patch('/me/email',          auth, validate(v.changeEmailSchema),      ctrl.changeEmail);
router.post ('/me/profile-picture',auth, uploadLimiter, ...ctrl.uploadProfilePicture);

module.exports = router;
