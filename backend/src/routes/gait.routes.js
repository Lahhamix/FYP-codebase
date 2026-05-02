const router   = require('express').Router();
const ctrl     = require('../controllers/gait.controller');
const auth     = require('../middleware/authenticate');
const validate = require('../middleware/validate');
const v        = require('../validators/gait.validator');

router.post('/',        auth, validate(v.createGaitSchema), ctrl.create);
router.get ('/latest',  auth,                                ctrl.latest);
router.get ('/history', auth,                                ctrl.history);

module.exports = router;
