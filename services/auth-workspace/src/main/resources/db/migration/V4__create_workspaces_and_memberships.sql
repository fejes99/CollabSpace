CREATE TABLE workspaces
(
  id                 UUID PRIMARY KEY,
  name               VARCHAR(255) NOT NULL,
  description        TEXT,
  created_by_user_id UUID         NOT NULL,
  created_at         TIMESTAMPTZ  NOT NULL,
  updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE workspace_memberships
(
  id           UUID PRIMARY KEY,
  workspace_id UUID        NOT NULL,
  user_id      UUID        NOT NULL,
  role         VARCHAR(20) NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL
);

ALTER TABLE workspaces
  ADD CONSTRAINT workspaces_created_by_user_id_fkey FOREIGN KEY (created_by_user_id) REFERENCES users (id);

ALTER TABLE workspace_memberships
  ADD CONSTRAINT workspace_memberships_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE;
ALTER TABLE workspace_memberships
  ADD CONSTRAINT workspace_memberships_user_id_fkey FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE workspace_memberships
  ADD CONSTRAINT workspace_memberships_role_check CHECK (role in ('admin', 'member'));
ALTER TABLE workspace_memberships
  ADD CONSTRAINT workspace_memberships_workspace_user_unique UNIQUE (workspace_id, user_id);

CREATE INDEX idx_workspace_memberships_user_id ON workspace_memberships (user_id);
