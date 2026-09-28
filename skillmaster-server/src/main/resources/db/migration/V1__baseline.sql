-- Baseline schema for v1-hosting: every table of technical-design.md ch.3.
--
-- Built in one migration rather than one per module because skill.created_by references
-- app_user, so the identity tables have to exist anyway; splitting them would only mean
-- churning the baseline again in P1.
--
-- Three deliberate divergences from the design doc, each explained where it appears:
--   1. `user` is renamed `app_user` (below).
--   2. `audit_event.id` is GENERATED ALWAYS AS IDENTITY (ADR 0010 decision, doc updated).
--   3. No TABLESPACE clause (below).
--
-- No extensions are required. The Chinese-search index (pg_bigm) is deliberately absent:
-- P0 queries L1 with LIKE, and pg_bigm is a pure index that can be added later without
-- changing a single query. That keeps P0 runnable on a stock PostgreSQL.

-- ---------------------------------------------------------------------------
-- 3.1 Identity and authentication
-- ---------------------------------------------------------------------------

-- `user` is a RESERVED word in PostgreSQL, so the design doc's `CREATE TABLE user (...)`
-- is a syntax error here (verified: pg_get_keywords() reports catcode 'R'). Renamed
-- rather than quoted: a quoted "user" is a footgun no compiler would catch, and the table
-- name carries no external contract.
CREATE TABLE app_user (
  id           TEXT PRIMARY KEY,               -- ULID
  handle       TEXT NOT NULL UNIQUE,           -- login name, also the personal namespace slug
  display_name TEXT NOT NULL DEFAULT '',
  email        TEXT UNIQUE,
  status       TEXT NOT NULL DEFAULT 'active', -- active | suspended
  created_at   TEXT NOT NULL
);

CREATE TABLE credential (                      -- self-hosted login; unused until P1
  user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  type        TEXT NOT NULL,                   -- password | totp
  secret_hash TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (user_id, type)
);

CREATE TABLE identity (                        -- reserved for SSO; costs nothing now
  provider    TEXT NOT NULL,                   -- lark | github | google
  external_id TEXT NOT NULL,
  user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  linked_at   TEXT NOT NULL,
  PRIMARY KEY (provider, external_id)
);

CREATE TABLE browser_session (                 -- the login page's browser session; P1
  session_id TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE oauth_client (
  client_id          TEXT PRIMARY KEY,         -- a CIMD client_id is an HTTPS URL
  name               TEXT NOT NULL,
  registration       TEXT NOT NULL,            -- cimd | dcr | preregistered
  redirect_uris      TEXT NOT NULL,            -- JSON array
  grant_types        TEXT NOT NULL,            -- JSON array
  client_secret_hash TEXT,                     -- confidential clients only
  metadata           TEXT NOT NULL DEFAULT '{}',
  created_at         TEXT NOT NULL
);

CREATE TABLE auth_code (
  code_hash      TEXT PRIMARY KEY,
  client_id      TEXT NOT NULL REFERENCES oauth_client(client_id),
  user_id        TEXT NOT NULL REFERENCES app_user(id),
  redirect_uri   TEXT NOT NULL,
  scope          TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  method         TEXT NOT NULL,                -- S256
  resource       TEXT,                         -- RFC 8707 audience
  expires_at     TEXT NOT NULL,
  used_at        TEXT
);

-- Tokens are stored as sha256 hashes only, never in clear (3.1 implementation constraint 1).
CREATE TABLE access_token (
  token_hash TEXT PRIMARY KEY,
  client_id  TEXT NOT NULL,
  user_id    TEXT NOT NULL REFERENCES app_user(id),
  scope      TEXT NOT NULL,
  audience   TEXT NOT NULL,                    -- must be validated; 3.1 constraint 2
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_at_expiry ON access_token(expires_at);

CREATE TABLE refresh_token (
  token_hash   TEXT PRIMARY KEY,
  client_id    TEXT NOT NULL,
  user_id      TEXT NOT NULL REFERENCES app_user(id),
  scope        TEXT NOT NULL,
  expires_at   TEXT NOT NULL,
  revoked_at   TEXT,
  rotated_from TEXT,                           -- rotation chain, so replay is detectable
  created_at   TEXT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 3.2 Namespaces and membership
-- ---------------------------------------------------------------------------

CREATE TABLE namespace (
  id            TEXT PRIMARY KEY,
  slug          TEXT NOT NULL UNIQUE,          -- used in URLs; the reserved one is 'skillmaster'
  title         TEXT NOT NULL DEFAULT '',
  owner_user_id TEXT NOT NULL REFERENCES app_user(id),
  visibility    TEXT NOT NULL DEFAULT 'private', -- public | unlisted | private
  created_at    TEXT NOT NULL
);

CREATE TABLE namespace_member (
  namespace_id TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
  user_id      TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role         TEXT NOT NULL,                  -- owner | editor | viewer (v1 writes 'owner' only)
  added_at     TEXT NOT NULL,
  PRIMARY KEY (namespace_id, user_id)
);

-- ---------------------------------------------------------------------------
-- 3.3 Skills, versions, content-addressed blobs
-- ---------------------------------------------------------------------------

-- Bytes live in their own table, and the metadata row is separate from them, so the two can
-- be backed up on different schedules and so blob pages cannot evict metadata pages from
-- shared_buffers (ADR 0010 decision 2).
--
-- The design doc places blob_content in TABLESPACE blob_ts. That clause is NOT used here:
-- a tablespace has to exist before it can be named, and CREATE TABLESPACE requires superuser,
-- so it cannot be created from a migration. Moving the table is a deployment step:
--   ALTER TABLE blob_content SET TABLESPACE blob_ts;
CREATE TABLE blob (
  sha256     TEXT PRIMARY KEY,
  size       INTEGER NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE blob_content (
  sha256 TEXT PRIMARY KEY REFERENCES blob(sha256) ON DELETE CASCADE,
  bytes  BYTEA NOT NULL
);

-- `id` is the identity and `name` is an attribute (ADR 0004): a rename is an UPDATE to
-- skill.name and nothing else — no URL, reference, or audit row moves.
-- `current_version_id` deliberately has no foreign key: it points at skill_version, which
-- points back at skill, so a FK would be circular. The pointer is only ever moved inside
-- the publish transaction.
CREATE TABLE skill (
  id                 TEXT PRIMARY KEY,
  namespace_id       TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
  name               TEXT NOT NULL,
  title              TEXT NOT NULL DEFAULT '',
  description        TEXT NOT NULL,            -- hot path, denormalised from frontmatter
  frontmatter        TEXT NOT NULL,            -- raw frontmatter as JSON; unknown fields pass through
  visibility         TEXT NOT NULL DEFAULT 'private',
  current_version_id TEXT,
  created_by         TEXT NOT NULL REFERENCES app_user(id),
  created_at         TEXT NOT NULL,
  updated_at         TEXT NOT NULL,
  deleted_at         TEXT,                     -- soft delete
  UNIQUE (namespace_id, name)
);
CREATE INDEX idx_skill_ns  ON skill(namespace_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_skill_vis ON skill(visibility)   WHERE deleted_at IS NULL;

-- UNIQUE (skill_id, digest) is what makes publishing idempotent: identical content does not
-- produce a new version, so no "read then write" race exists (ADR 0005).
CREATE TABLE skill_version (
  id           TEXT PRIMARY KEY,
  skill_id     TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  digest       TEXT NOT NULL,                  -- deterministic digest over the file set
  file_count   INTEGER NOT NULL,
  total_bytes  INTEGER NOT NULL,
  changelog    TEXT NOT NULL DEFAULT '',
  source       TEXT NOT NULL DEFAULT '',       -- provenance note: upload | zip | git url
  published_by TEXT NOT NULL REFERENCES app_user(id),
  published_at TEXT NOT NULL,
  UNIQUE (skill_id, digest)
);
CREATE INDEX idx_ver_skill ON skill_version(skill_id, published_at DESC);

-- The manifest is this table projected and explicitly sorted by relpath — never by whatever
-- order the database happens to return (ADR 0005 后果).
CREATE TABLE version_file (
  version_id  TEXT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE,
  relpath     TEXT NOT NULL,                   -- relative to the skill root, POSIX separators
  blob_sha256 TEXT NOT NULL REFERENCES blob(sha256),
  size        INTEGER NOT NULL,
  is_binary   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (version_id, relpath)
);

-- ---------------------------------------------------------------------------
-- 3.4 Search and usage statistics
-- ---------------------------------------------------------------------------

-- Only L1 is indexed — name, title, description. Never the body or the files: that is a
-- safety constraint, not a performance one, because a full-text index over the body would
-- let fragments of it be reconstructed through search (ADR 0006).
--
-- This table is written in P1; P0 has no usage signal to record.
CREATE TABLE skill_stat (
  skill_id       TEXT PRIMARY KEY REFERENCES skill(id) ON DELETE CASCADE,
  search_hits    INTEGER NOT NULL DEFAULT 0,
  detail_views   INTEGER NOT NULL DEFAULT 0,
  body_reads     INTEGER NOT NULL DEFAULT 0,
  file_reads     INTEGER NOT NULL DEFAULT 0,
  last_access_at TEXT
);

-- ---------------------------------------------------------------------------
-- 3.5 Audit
-- ---------------------------------------------------------------------------

-- Publishing, deleting, rolling back, and issuing or revoking a token all leave a trace:
-- skill content is taken away and used in client environments, so after-the-fact attribution
-- is the floor (3.5).
CREATE TABLE audit_event (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_user_id TEXT,
  action        TEXT NOT NULL,                 -- publish | delete | rollback | token_issue | ...
  target_type   TEXT NOT NULL,
  target_id     TEXT,
  detail        TEXT NOT NULL DEFAULT '{}',
  at            TEXT NOT NULL
);
CREATE INDEX idx_audit_at ON audit_event(at DESC);
