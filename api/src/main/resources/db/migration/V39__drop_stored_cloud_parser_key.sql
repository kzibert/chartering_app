-- The cloud parser's API key is no longer the installation's: each person's browser holds their
-- own and sends it with their Intake requests (ParserRoute), and the server never stores it.
-- The one this table held - encrypted under CREDENTIALS_KEY, shared by everybody on the
-- installation - is deleted rather than left behind as a secret nothing reads.
--
-- app_settings is under forced row-level security (V36), and the key was an installation row
-- (NULL tenant_id), so the migration needs the bypass to see it.
SET LOCAL app.rls_bypass = 'on';

DELETE FROM app_settings WHERE key = 'parser.cloudApiKey';
