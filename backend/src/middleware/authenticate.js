const { verifyAccess } = require('../config/jwt');

module.exports = function authenticate(req, res, next) {
  const header = req.headers.authorization;
  if (!header || !header.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Missing or invalid Authorization header.' });
  }
  try {
    const payload = verifyAccess(header.slice(7));
    req.user = { userId: payload.sub };
    next();
  } catch {
    res.status(401).json({ error: 'Invalid or expired access token.' });
  }
};
