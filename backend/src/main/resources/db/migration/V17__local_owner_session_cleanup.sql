UPDATE session_tokens
SET revoked_at = now()
WHERE revoked_at IS NULL
  AND user_id <> 'u-local-owner';
