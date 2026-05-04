const fs   = require('fs');
const path = require('path');
const { Pool } = require('pg');
const env = require('../src/config/env');

const pool = new Pool({
  connectionString: env.DATABASE_URL,
  ssl: env.NODE_ENV === 'production' ? { rejectUnauthorized: false } : false,
});

async function run() {
  const files = fs.readdirSync(__dirname)
    .filter((file) => /^\d+_.+\.sql$/.test(file))
    .sort();
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    for (const file of files) {
      const sql = fs.readFileSync(path.join(__dirname, file), 'utf8');
      await client.query(sql);
      console.log(`[migration] ${file} OK`);
    }
    await client.query('COMMIT');
    console.log('Migrations complete.');
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('Migration failed:', err.message);
    process.exit(1);
  } finally {
    client.release();
    await pool.end();
  }
}

run();
