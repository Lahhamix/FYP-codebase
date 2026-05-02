const svc    = require('../services/user.service');
const multer = require('multer');
const path   = require('path');
const fs     = require('fs');
const env    = require('../config/env');

const uploadDir = path.resolve(env.UPLOAD_DIR);
if (!fs.existsSync(uploadDir)) fs.mkdirSync(uploadDir, { recursive: true });

const upload = multer({
  dest:   uploadDir,
  limits: { fileSize: 5 * 1024 * 1024 },
  fileFilter: (_, file, cb) => {
    if (/image\/(jpeg|png|webp)/.test(file.mimetype)) cb(null, true);
    else cb(Object.assign(new Error('Only JPEG/PNG/WEBP images are allowed.'), { status: 400 }));
  },
});

const wrap = fn => async (req, res, next) => {
  try { await fn(req, res); } catch (e) { next(e); }
};

exports.getMe = wrap(async (req, res) => {
  const data = await svc.getMe(req.user.userId);
  res.json(data);
});

exports.updateProfile = wrap(async (req, res) => {
  const data = await svc.updateProfile(req.user.userId, req.body);
  res.json(data);
});

exports.changeUsername = wrap(async (req, res) => {
  await svc.changeUsername(req.user.userId, req.body.username);
  res.json({ message: 'Username updated.' });
});

exports.changeEmail = wrap(async (req, res) => {
  await svc.changeEmail(req.user.userId, req.body.email);
  res.json({ message: 'Email change initiated. Please verify your new email address.' });
});

exports.uploadProfilePicture = [
  upload.single('picture'),
  wrap(async (req, res) => {
    if (!req.file) throw Object.assign(new Error('No file uploaded.'), { status: 400 });
    const url = `/uploads/${req.file.filename}`;
    await svc.updateProfilePicture(req.user.userId, url);
    res.json({ url });
  }),
];
