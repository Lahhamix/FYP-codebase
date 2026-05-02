-- email_verification_codes is replaced by the code fields in pending_registrations.
-- Verification codes are now stored there until the user is promoted to the users table.
DROP TABLE IF EXISTS email_verification_codes;
