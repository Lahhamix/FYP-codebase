const router   = require('express').Router();
const ctrl     = require('../controllers/pressureMatrix.controller');
const auth     = require('../middleware/authenticate');
const validate = require('../middleware/validate');
const v        = require('../validators/pressureMatrix.validator');

router.post('/',        auth, validate(v.createMatrixSchema), ctrl.create);
router.get ('/latest',  auth,                                  ctrl.latest);
router.get ('/history', auth,                                  ctrl.history);

module.exports = router;
