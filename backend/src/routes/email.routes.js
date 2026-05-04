const router      = require('express').Router();
const auth        = require('../middleware/authenticate');
const emailService = require('../services/email.service');

router.post('/send-email', auth, async (req, res, next) => {
  try {
    const { to, subject, text, html } = req.body;
    if (!to || !subject || !text) {
      return res.status(400).json({ error: 'Missing required fields: to, subject, text' });
    }
    await emailService.send({ to, subject, text, html });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

module.exports = router;
