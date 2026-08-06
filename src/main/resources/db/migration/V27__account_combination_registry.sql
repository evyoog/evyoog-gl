-- ============================================================
-- V27: Account Combination Registry
--
-- Note: this capability is internally referred to as "Account
-- Combination Registry" without a GL-NN capability code — GL-27 is
-- already taken by GST Export (V16__gl27_gst_export.sql) and GL-28
-- by TDS Recording (V17__gl28_tds_recording.sql). Do not reuse either
-- number for this capability.
-- ============================================================

-- Step 1: Add allow_dynamic_insert to gl.ledger
ALTER TABLE gl.ledger
  ADD COLUMN IF NOT EXISTS allow_dynamic_insert BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN gl.ledger.allow_dynamic_insert IS
  'When TRUE, unknown combinations are auto-registered on first posting.
   When FALSE, only pre-approved combinations in gl.account_combination
   may be posted. Default TRUE (open mode).';

-- Step 2: Create gl.account_combination table
CREATE TABLE IF NOT EXISTS gl.account_combination (
  id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  ledger_id             UUID NOT NULL REFERENCES gl.ledger(id),
  legal_entity_id       UUID NOT NULL REFERENCES gl.legal_entity(id),
  combination           JSONB NOT NULL,
  combination_code      VARCHAR(255),
  description           VARCHAR(500),
  is_active             BOOLEAN NOT NULL DEFAULT TRUE,
  is_dynamic            BOOLEAN NOT NULL DEFAULT FALSE,
  first_used_at         TIMESTAMP WITH TIME ZONE,
  created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  created_by            VARCHAR(100) NOT NULL,
  updated_by            VARCHAR(100) NOT NULL,
  UNIQUE (ledger_id, legal_entity_id, combination)
);

-- GIN index for JSONB containment queries
CREATE INDEX IF NOT EXISTS idx_account_combination_combination
  ON gl.account_combination USING GIN (combination);

CREATE INDEX IF NOT EXISTS idx_account_combination_ledger_le
  ON gl.account_combination (ledger_id, legal_entity_id);

COMMENT ON TABLE gl.account_combination IS
  'Registry of approved account combinations. When allow_dynamic_insert=TRUE
   on the ledger, new combinations are auto-registered on first posting.
   When FALSE, only combinations in this table may be posted.';

COMMENT ON COLUMN gl.account_combination.is_dynamic IS
  'TRUE = auto-registered during posting. FALSE = manually pre-approved.';

-- Step 3: Seed existing combinations from journal_line data
-- Auto-register all combinations currently in use
INSERT INTO gl.account_combination (
  ledger_id, legal_entity_id, combination, combination_code,
  description, is_active, is_dynamic, first_used_at,
  created_by, updated_by
)
SELECT DISTINCT
  jh.ledger_id,
  jh.legal_entity_id,
  jl.account_combination,
  -- Build combination code from JSONB values
  COALESCE(jl.account_combination->>'NATURAL_ACCOUNT', '') ||
  CASE WHEN jl.account_combination->>'COST_CENTRE' IS NOT NULL
    THEN '.' || (jl.account_combination->>'COST_CENTRE') ELSE '' END ||
  CASE WHEN jl.account_combination->>'PRODUCT' IS NOT NULL
    THEN '.' || (jl.account_combination->>'PRODUCT') ELSE '' END,
  'Auto-registered from existing journal data',
  TRUE,
  TRUE,  -- is_dynamic = TRUE (auto-registered)
  MIN(jl.created_at),
  'V27_MIGRATION',
  'V27_MIGRATION'
FROM gl.journal_line jl
JOIN gl.journal_header jh ON jh.id = jl.journal_header_id
WHERE jl.account_combination IS NOT NULL
  AND jl.account_combination != '{}'::jsonb
GROUP BY jh.ledger_id, jh.legal_entity_id, jl.account_combination
ON CONFLICT (ledger_id, legal_entity_id, combination) DO NOTHING;
