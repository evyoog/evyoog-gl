-- ============================================================
-- V29: COA Structure
--
-- Note: the build prompt for this capability assumed the next free
-- migration was V30. The actual latest migration at the time of this
-- build was V28 (default_dimension_value) — this is V29. Always check
-- `ls src/main/resources/db/migration | sort -V | tail -1` rather than
-- trusting a number handed to you in a prompt.
--
-- Introduces gl.coa_structure as a reusable accounting framework that
-- can be assigned to one or more Ledgers. gl.finance_dimension rows
-- become the COA's segments by adding coa_structure_id; ledger_id on
-- finance_dimension stays (nullable now) purely for backward
-- compatibility with existing ledger-scoped queries.
-- ============================================================

-- Step 1: Create gl.coa_structure table
CREATE TABLE IF NOT EXISTS gl.coa_structure (
  id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  business_group_id UUID NOT NULL REFERENCES gl.business_group(id),
  code              VARCHAR(50) NOT NULL,
  name              VARCHAR(255) NOT NULL,
  description       VARCHAR(500),
  separator         VARCHAR(5) NOT NULL DEFAULT '.',
  is_active         BOOLEAN NOT NULL DEFAULT TRUE,
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  created_by        VARCHAR(100) NOT NULL,
  updated_by        VARCHAR(100) NOT NULL,
  UNIQUE (business_group_id, code)
);

COMMENT ON TABLE gl.coa_structure IS
  'Defines the segment structure of a Chart of Accounts.
   A COA Structure can be assigned to multiple Ledgers (shared structure).
   Each segment is a row in gl.finance_dimension with coa_structure_id set.';

CREATE INDEX IF NOT EXISTS idx_coa_structure_business_group
  ON gl.coa_structure (business_group_id);

-- Step 2: Add coa_structure_id to gl.finance_dimension
ALTER TABLE gl.finance_dimension
  ADD COLUMN IF NOT EXISTS coa_structure_id UUID REFERENCES gl.coa_structure(id);

CREATE INDEX IF NOT EXISTS idx_finance_dimension_coa_structure
  ON gl.finance_dimension (coa_structure_id);

-- Make ledger_id nullable (backward compat — will be removed in future migration)
ALTER TABLE gl.finance_dimension
  ALTER COLUMN ledger_id DROP NOT NULL;

COMMENT ON COLUMN gl.finance_dimension.coa_structure_id IS
  'Links this dimension to a COA Structure. Preferred over ledger_id.
   ledger_id is kept for backward compatibility during migration.';

-- Step 3: Add coa_structure_id to gl.ledger
ALTER TABLE gl.ledger
  ADD COLUMN IF NOT EXISTS coa_structure_id UUID REFERENCES gl.coa_structure(id);

COMMENT ON COLUMN gl.ledger.coa_structure_id IS
  'The COA Structure assigned to this Ledger. Defines which segments
   are active and their order for account combinations.';

-- Step 4: Migrate existing data — create a default COA Structure from the
-- existing Orbinox finance dimensions, derived via the real ledger ->
-- legal_entity -> business_group chain (not an arbitrary business group row).
-- On a fresh (e.g. Testcontainers) database with no matching ledger, every
-- statement below simply affects zero rows.
WITH ledger_bg AS (
  SELECT DISTINCT le.business_group_id
  FROM gl.legal_entity_ledger lel
  JOIN gl.legal_entity le ON le.id = lel.legal_entity_id
  WHERE lel.ledger_id = 'b97398f5-146d-40fe-9dc0-2601095bde1a'
  LIMIT 1
),
new_coa AS (
  INSERT INTO gl.coa_structure (
    business_group_id, code, name, description,
    separator, is_active, created_by, updated_by
  )
  SELECT
    ledger_bg.business_group_id,
    'STD-IND-MFG',
    'Standard India Manufacturing COA',
    'Standard COA structure for Indian manufacturing companies. Segments: Natural Account + Cost Centre + Product.',
    '.',
    TRUE,
    'V29_MIGRATION',
    'V29_MIGRATION'
  FROM ledger_bg
  RETURNING id
)
-- 4a: Link existing finance_dimensions to the new COA Structure
UPDATE gl.finance_dimension fd
SET coa_structure_id = new_coa.id,
    updated_at = NOW(),
    updated_by = 'V29_MIGRATION'
FROM new_coa
WHERE fd.ledger_id = 'b97398f5-146d-40fe-9dc0-2601095bde1a';

-- 4b: Link existing ledger to the new COA Structure
UPDATE gl.ledger l
SET coa_structure_id = cs.id,
    updated_at = NOW(),
    updated_by = 'V29_MIGRATION'
FROM gl.coa_structure cs
WHERE cs.code = 'STD-IND-MFG'
  AND l.id = 'b97398f5-146d-40fe-9dc0-2601095bde1a';
