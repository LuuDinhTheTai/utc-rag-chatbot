-- Usage: psql -v ON_ERROR_STOP=1 -v email=your-registered-email -f bootstrap-admin.sql
-- Register the account using the API first. No default account/password is created.
BEGIN;
LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
SELECT id AS bootstrap_user FROM users WHERE email=lower(:'email') AND status='ACTIVE' \gset
INSERT INTO role_assignments(user_id,role_code,campus_id,assigned_at)
VALUES(:'bootstrap_user'::uuid,'ADMIN',NULL,now()) ON CONFLICT DO NOTHING;
UPDATE users SET auth_epoch=auth_epoch+1 WHERE id=:'bootstrap_user'::uuid;
INSERT INTO audit_events(actor_user_id,action,entity_type,entity_key,details,created_at)
VALUES(NULL,'BOOTSTRAP_ADMIN','users',:'bootstrap_user','{"channel":"local-cli"}',now());
COMMIT;
