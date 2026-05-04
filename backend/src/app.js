const express = require('express');
const helmet  = require('helmet');
const cors    = require('cors');
const path    = require('path');

const app = express();

// Trust Railway's reverse proxy so express-rate-limit can read X-Forwarded-For
app.set('trust proxy', 1);

app.use(helmet());
app.use(cors({ origin: false }));
app.use(express.json({ limit: '1mb' }));

// Serve uploaded profile pictures
app.use('/uploads', express.static(path.resolve(process.env.UPLOAD_DIR || 'uploads')));

// ── Routes ────────────────────────────────────────────────────
app.use('/auth',            require('./routes/auth.routes'));
app.use('/users',           require('./routes/user.routes'));
app.use('/settings',        require('./routes/settings.routes'));
app.use('/devices',         require('./routes/device.routes'));
app.use('/readings',        require('./routes/reading.routes'));
app.use('/pressure-matrix', require('./routes/pressureMatrix.routes'));
app.use('/gait',            require('./routes/gait.routes'));
app.use('/alerts',          require('./routes/alert.routes'));
app.use('/auto-share',      require('./routes/autoShare.routes'));
app.use('/feedback',        require('./routes/feedback.routes'));

const emailSvc = require('./services/email.service');
app.post('/send-email', async (req, res, next) => {
  try {
    const { type, to, patientName, code } = req.body;
    if (!type || !to) return res.status(400).json({ error: 'Missing required fields: type, to' });
    switch (type) {
      case 'share-verification':
        if (!code) return res.status(400).json({ error: 'Missing field: code' });
        await emailSvc.sendShareVerification(to, patientName, code);
        break;
      case 'sharing-stopped':
        await emailSvc.sendSharingStoppedEmail(to, patientName);
        break;
      case 'sharing-disabled':
        await emailSvc.sendAlertSharingDisabledEmail(to, patientName);
        break;
      default:
        return res.status(400).json({ error: `Unknown email type: ${type}` });
    }
    res.json({ ok: true });
  } catch (err) { next(err); }
});

app.get('/health', (_, res) => res.json({ ok: true, version: '2.0.0' }));

app.use(require('./middleware/errorHandler'));

module.exports = app;
