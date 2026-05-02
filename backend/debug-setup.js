const fs   = require('fs');
const path = require('path');
const { Pool } = require('pg');
require('dotenv').config({ path: path.resolve(__dirname, '.env') });

console.log('=== Debug Setup ===');
console.log('DATABASE_URL:', process.env.DATABASE_URL.replace(/:[^:]*@/, ':***@'));

const pool = new Pool({ connectionString: process.env.DATABASE_URL });

async function run() {
  try {
    // Test connection
    const result = await pool.query('SELECT NOW()');
    console.log('✓ Database connection successful');
    console.log('  Server time:', result.rows[0].now);

    // Check if tables exist
    const tablesResult = await pool.query(`
      SELECT table_name 
      FROM information_schema.tables 
      WHERE table_schema = 'public'
      ORDER BY table_name
    `);
    
    console.log('\n✓ Existing tables:', tablesResult.rows.length);
    if (tablesResult.rows.length === 0) {
      console.log('  NO TABLES FOUND - Running migrations now...\n');
      
      const sql = fs.readFileSync(path.join(__dirname, 'migrations/001_initial_schema.sql'), 'utf8');
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        await client.query(sql);
        await client.query('COMMIT');
        console.log('✓ Migration completed successfully');
      } catch (err) {
        await client.query('ROLLBACK');
        console.error('✗ Migration failed:', err.message);
        process.exit(1);
      } finally {
        client.release();
      }
    } else {
      console.log('  Tables found:');
      tablesResult.rows.forEach(r => console.log(`    - ${r.table_name}`));
    }

  } catch (err) {
    console.error('✗ Error:', err.message);
    process.exit(1);
  } finally {
    await pool.end();
  }
}

run();
