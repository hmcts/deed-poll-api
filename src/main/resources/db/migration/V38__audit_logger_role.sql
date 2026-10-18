-- V38: Create least-privilege audit logger role for OWASP Plan 07 Phase 2
-- Steve approved 2026-10-18: Audit tables only, no context reads

-- Create the deedpoll_audit_logger role
-- This role is used by the audit-logging component to write and verify audit events.
-- It has minimal privileges: INSERT/SELECT on audit tables only.
CREATE ROLE deedpoll_audit_logger WITH LOGIN;

-- Grant permissions on audit event tables (write + read)
GRANT INSERT ON deedpoll_audit_event TO deedpoll_audit_logger;
GRANT INSERT ON deedpoll_audit_event_value TO deedpoll_audit_logger;
GRANT SELECT ON deedpoll_audit_event TO deedpoll_audit_logger;
GRANT SELECT ON deedpoll_audit_event_value TO deedpoll_audit_logger;

-- Explicitly deny dangerous operations (defensive)
REVOKE UPDATE, DELETE, TRUNCATE ON deedpoll_audit_event FROM deedpoll_audit_logger;
REVOKE UPDATE, DELETE, TRUNCATE ON deedpoll_audit_event_value FROM deedpoll_audit_logger;

-- Principle of least privilege: no access to other tables
-- Default privileges for future tables: no access
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM deedpoll_audit_logger;
