require('./src/config/env');   // validates env vars early

const app = require('./src/app');
const env = require('./src/config/env');

app.listen(env.PORT, () => {
  console.log(`SoleMate backend v2 running on port ${env.PORT} [${env.NODE_ENV}]`);
});
