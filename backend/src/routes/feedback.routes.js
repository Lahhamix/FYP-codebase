const router   = require('express').Router();
const ctrl     = require('../controllers/feedback.controller');
const auth     = require('../middleware/authenticate');
const validate = require('../middleware/validate');
const v        = require('../validators/feedback.validator');

router.post('/my', auth, validate(v.createFeedbackSchema), ctrl.create);
router.get ('/my', auth,                                     ctrl.listMy);

module.exports = router;
