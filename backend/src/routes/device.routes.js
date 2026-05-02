const router   = require('express').Router();
const ctrl     = require('../controllers/device.controller');
const auth     = require('../middleware/authenticate');
const validate = require('../middleware/validate');
const v        = require('../validators/device.validator');

router.get   ('/',    auth,                                   ctrl.list);
router.post  ('/',    auth, validate(v.createDeviceSchema),   ctrl.create);
router.patch ('/:id', auth, validate(v.updateDeviceSchema),   ctrl.update);
router.delete('/:id', auth,                                    ctrl.remove);

module.exports = router;
