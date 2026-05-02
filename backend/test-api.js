const http = require('http');

const timestamp = Date.now();
const testData = {
  email: `test${timestamp}@example.com`,
  username: `testuser${timestamp}`,
  password: 'TestPass@123'
};

const jsonData = JSON.stringify(testData);

const options = {
  hostname: 'localhost',
  port: 3000,
  path: '/auth/register',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Content-Length': jsonData.length
  }
};

console.log('=== Testing Signup ===');
console.log('Email:', testData.email);
console.log('Username:', testData.username);
console.log('Password:', testData.password);
console.log('\nSending request to backend...\n');

const req = http.request(options, (res) => {
  let body = '';
  res.on('data', chunk => body += chunk);
  res.on('end', () => {
    console.log('HTTP Status:', res.statusCode);
    try {
      const response = JSON.parse(body);
      console.log('Response:', JSON.stringify(response, null, 2));
    } catch {
      console.log('Raw Response:', body);
    }
    process.exit(res.statusCode === 201 ? 0 : 1);
  });
});

req.on('error', (e) => {
  console.error('❌ Request error:', e.message);
  process.exit(1);
});

req.write(jsonData);
req.end();

setTimeout(() => {
  console.error('❌ Request timeout');
  process.exit(1);
}, 10000);
