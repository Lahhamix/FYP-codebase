module.exports = function errorHandler(err, req, res, next) {
  const status  = err.status || 500;
  const message = status < 500 ? err.message : 'Internal server error.';
  if (status >= 500) console.error(err);
  res.status(status).json({ error: message, ...(err.code && { code: err.code }) });
};
