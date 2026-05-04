-- Keep existing databases compatible with the Android app payload contract.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE IF EXISTS health_readings
  ALTER COLUMN swelling_value TYPE TEXT
  USING swelling_value::text;
