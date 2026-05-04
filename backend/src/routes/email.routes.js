const router   = require('express').Router();
const emailSvc = require('../services/email.service');

router.post('/send-email', async (req, res, next) => {
  try {
    const { to, subject, text, html } = req.body;
    if (!to || !subject) {
      return res.status(400).json({ error: 'Missing required fields: to, subject' });
    }
    await emailSvc.send({ to, subject, text, html });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

module.exports = router;
