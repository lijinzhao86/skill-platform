-- Empties everything a test can write, and nothing else.
--
-- The seed rows of V2 — three users, three namespaces, three owner memberships — are identity
-- data the tests read, not fixtures they create, so they stay. Everything below is created by
-- publishing or deleting, and leaving it in place would make a test's result depend on which
-- tests ran before it.
--
-- RESTART IDENTITY is required rather than cosmetic: audit_event.id is GENERATED ALWAYS AS
-- IDENTITY, and a test asserting on id 1 would otherwise pass or fail by ordering.
--
-- Not truncated: namespace_member and the P1 auth tables (credential, identity, browser_session,
-- oauth_client, auth_code, access_token, refresh_token). P0a never writes them, so emptying them
-- would only move the database away from its migrated shape for no gain.
TRUNCATE TABLE
    blob_content,
    version_file,
    skill_version,
    skill,
    skill_stat,
    audit_event,
    blob
RESTART IDENTITY CASCADE;
