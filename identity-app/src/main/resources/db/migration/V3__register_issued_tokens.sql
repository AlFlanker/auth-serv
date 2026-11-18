-- Записи V1/V2 сохраняют revoked_at. Активная запись создаётся только при выдаче JWT.
ALTER TABLE token_registry ALTER COLUMN revoked_at DROP NOT NULL;
ALTER TABLE token_registry ADD CONSTRAINT ck_token_registry_active_expiry
    CHECK (revoked_at IS NOT NULL OR expires_at IS NOT NULL);
