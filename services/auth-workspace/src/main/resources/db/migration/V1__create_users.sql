CREATE TABLE users
(
  id            UUID PRIMARY KEY,
  name          VARCHAR(255) NOT NULL,
  email         VARCHAR(320) NOT NULL UNIQUE,
  password_hash TEXT,
  created_at    TIMESTAMPTZ  NOT NULL,
  updated_at    TIMESTAMPTZ  NOT NULL
);
