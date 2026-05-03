/**
 * Runs via Jest "setupFilesAfterEnv" — executes once per test file after the
 * Jest framework is installed.  Registers global before/after hooks that wipe
 * test data so every test file starts with a clean slate.
 *
 * All test fixtures use emails matching "jesttest_%@example.com" which makes
 * it safe to DELETE them without touching real user rows.
 */
const db = require('../src/config/db');

async function cleanTestData() {
  // Delete in FK dependency order
  await db.query("DELETE FROM users WHERE email LIKE 'jesttest_%@example.com'");
  await db.query("DELETE FROM pending_registrations WHERE email LIKE 'jesttest_%@example.com'");
}

// Clean before each file so leftover data from a previous failed run never
// pollutes the next run. No afterAll needed — maxWorkers:1 ensures files run
// serially so there is no cross-file race on shared test rows.
beforeAll(async () => {
  await cleanTestData();
});

// Expose globally so individual test files can call it if needed
global.cleanTestData = cleanTestData;
