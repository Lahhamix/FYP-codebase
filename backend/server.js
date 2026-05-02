require('./src/config/env');   // validates env vars early

const fs   = require('fs');
const path = require('path');
const db   = require('./src/config/db');
const app  = require('./src/app');
const env  = require('./src/config/env');

async function runMigrations() {
  const dir   = path.join(__dirname, 'migrations');
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.sql')).sort();
  for (const file of files) {
    const sql = fs.readFileSync(path.join(dir, file), 'utf8');
    await db.query(sql);
    console.log(`[migration] ${file} OK`);
  }
}

runMigrations()
  .then(() => {
    app.listen(env.PORT, () => {
      console.log(`SoleMate backend v2 running on port ${env.PORT} [${env.NODE_ENV}]`);
    });
  })
  .catch(err => {
    console.error('[migration] FAILED:', err.message);
    process.exit(1);
  });
