CREATE TABLE refresh_tokens
(
  id         UUID PRIMARY KEY,
  user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  token_hash TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  user_agent TEXT,
  ip_address INET
);

ALTER TABLE refresh_tokens
  ADD CONSTRAINT refresh_tokens_token_hash_unique UNIQUE (token_hash);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
