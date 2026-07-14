CREATE TABLE workspaces
(
  id                 UUID PRIMARY KEY,
  name               VARCHAR(255) NOT NULL,
  description        TEXT,
  created_by_user_id UUID         NOT NULL REFERENCES users (id),
  created_at         TIMESTAMPTZ  NOT NULL,
  updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE workspace_memberships
(
  id           UUID PRIMARY KEY,
  workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
  user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  role         VARCHAR(20) NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL
);

ALTER TABLE workspace_memberships
  ADD CONSTRAINT workspace_memberships_role_check CHECK (role in ('admin', 'member'));
ALTER TABLE workspace_memberships
  ADD CONSTRAINT workspace_memberships_workspace_user_unique UNIQUE (workspace_id, user_id);

CREATE INDEX idx_workspace_memberships_user_id ON workspace_memberships (user_id);
