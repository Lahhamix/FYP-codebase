const fs = require('fs');
const path = require('path');
const { Pool } = require('pg');
require('dotenv').config({ path: path.resolve(__dirname, '../.env') });

const pool = new Pool({ connectionString: process.env.DATABASE_URL });

async function run() {
  const client = await pool.connect();
  try {
    console.log('🗑️  Dropping all tables...');
    await client.query('BEGIN');
    
    // Drop all tables
    await client.query(`
      DROP TABLE IF EXISTS refresh_tokens CASCADE;
      DROP TABLE IF EXISTS password_reset_tokens CASCADE;
      DROP TABLE IF EXISTS email_verification_codes CASCADE;
      DROP TABLE IF EXISTS google_auth CASCADE;
      DROP TABLE IF EXISTS user_settings CASCADE;
      DROP TABLE IF EXISTS user_profiles CASCADE;
      DROP TABLE IF EXISTS auto_share_recipients CASCADE;
      DROP TABLE IF EXISTS alerts CASCADE;
      DROP TABLE IF EXISTS gait_analytics CASCADE;
      DROP TABLE IF EXISTS pressure_matrix_readings CASCADE;
      DROP TABLE IF EXISTS health_readings CASCADE;
      DROP TABLE IF EXISTS devices CASCADE;
      DROP TABLE IF EXISTS feedback CASCADE;
      DROP TABLE IF EXISTS users CASCADE;
      DROP TABLE IF EXISTS pending_registrations CASCADE;
    `);
    
    console.log('✓ All tables dropped');
    
    // Now run the new schema
    const sql = fs.readFileSync(path.join(__dirname, '001_initial_schema.sql'), 'utf8');
    await client.query(sql);
    
    console.log('✓ New schema created successfully');
    await client.query('COMMIT');
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('❌ Error:', err.message);
    process.exit(1);
  } finally {
    client.release();
    await pool.end();
  }
}

run().then(() => {
  console.log('\n✅ Database reset complete!');
  process.exit(0);
});
