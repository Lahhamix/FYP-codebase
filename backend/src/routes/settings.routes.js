const router   = require('express').Router();
const ctrl     = require('../controllers/settings.controller');
const auth     = require('../middleware/authenticate');
const validate = require('../middleware/validate');
const v        = require('../validators/settings.validator');

router.get  ('/', auth,                                    ctrl.get);
router.patch('/', auth, validate(v.updateSettingsSchema),  ctrl.update);

module.exports = router;
