-- У записей V1 срок действия JWT неизвестен. Сохраняем их до ручной очистки.
ALTER TABLE token_registry ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX ix_token_registry_expires_at ON token_registry (expires_at);
