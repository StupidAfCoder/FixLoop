-- Removes the throwaway accounts created by infra/smoke/auth-smoke.sh.
-- Safe on local or disposable data only: it deletes every "smoke-*" account.
BEGIN;

DELETE FROM refresh_tokens
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'smoke-%@%');

DELETE FROM notifications_outbox
WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'smoke-%@%');

DELETE FROM users WHERE email LIKE 'smoke-%@%';

COMMIT;
