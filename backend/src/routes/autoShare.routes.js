const router   = require('express').Router();
const ctrl     = require('../controllers/autoShare.controller');
const auth     = require('../middleware/authenticate');
const validate = require('../middleware/validate');
const v        = require('../validators/autoShare.validator');

router.get   ('/recipients',     auth,                                       ctrl.list);
router.post  ('/recipients',     auth, validate(v.createRecipientSchema),    ctrl.create);
router.patch ('/recipients/:id', auth, validate(v.updateRecipientSchema),    ctrl.update);
router.delete('/recipients/:id', auth,                                        ctrl.remove);

module.exports = router;
