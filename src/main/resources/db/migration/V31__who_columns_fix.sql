-- ============================================================
-- V31: WHO Column Fixes
-- Adds missing updated_by (and created_by/updated_at where missing) to
-- tables that lacked them. All new columns are nullable to
-- preserve backward compatibility with existing rows.
--
-- Column types match each table's existing created_by sibling column,
-- not a blanket VARCHAR(255):
--   auth.users / auth.roles / auth.approval_policy / gl.gstr_export_job
--     created_by is VARCHAR(100) -> updated_by is VARCHAR(100)
--   gl.coa_import_job
--     created_by is UUID -> updated_by is UUID
--   gl.provisioning_template
--     had no created_by at all -> created_by/updated_by both VARCHAR(100)
-- ============================================================

-- auth.users
ALTER TABLE auth.users
  ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- auth.roles
ALTER TABLE auth.roles
  ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- auth.approval_policy
ALTER TABLE auth.approval_policy
  ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- gl.coa_import_job (created_by is UUID here, not VARCHAR — match it)
ALTER TABLE gl.coa_import_job
  ADD COLUMN IF NOT EXISTS updated_by UUID;

-- gl.provisioning_template (had neither created_by nor updated_by)
ALTER TABLE gl.provisioning_template
  ADD COLUMN IF NOT EXISTS created_by VARCHAR(100),
  ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- gl.gstr_export_job (had no updated_at at all)
ALTER TABLE gl.gstr_export_job
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

COMMENT ON COLUMN auth.users.updated_by IS 'Email of user who last updated this record';
COMMENT ON COLUMN auth.roles.updated_by IS 'Email of user who last updated this role';
COMMENT ON COLUMN auth.approval_policy.updated_by IS 'Email of user who last updated this policy';
COMMENT ON COLUMN gl.coa_import_job.updated_by IS 'User id who last updated this import job';
COMMENT ON COLUMN gl.provisioning_template.created_by IS 'Email of user who created this template';
COMMENT ON COLUMN gl.provisioning_template.updated_by IS 'Email of user who last updated this template';
COMMENT ON COLUMN gl.gstr_export_job.updated_by IS 'Email of user who last updated this export job';
