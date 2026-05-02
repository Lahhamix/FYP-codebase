const router   = require('express').Router();
const ctrl     = require('../controllers/alert.controller');
const auth     = require('../middleware/authenticate');
const validate = require('../middleware/validate');
const v        = require('../validators/alert.validator');

router.post('/',           auth, validate(v.createAlertSchema), ctrl.create);
router.get ('/',           auth,                                  ctrl.list);
router.patch('/:id/status',auth, validate(v.updateAlertSchema),  ctrl.updateSent);

module.exports = router;
