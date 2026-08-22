-- ============================================================
-- V30: Balancing Segment Configuration
-- Adds is_balancing and balancing_sequence to finance_dimension.
-- V30a is configuration only -- no PostingEngine enforcement yet.
-- ============================================================

-- Add is_balancing flag
ALTER TABLE gl.finance_dimension
  ADD COLUMN IF NOT EXISTS is_balancing BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN gl.finance_dimension.is_balancing IS
  'When TRUE, this dimension is a secondary/tertiary balancing segment.
   Journals must balance independently within each value of this dimension.
   Legal Entity is always the implicit primary balancing segment (not stored here).
   V30a: configuration only. V30b: PostingEngine enforcement added.';

-- Add balancing_sequence for ordering
ALTER TABLE gl.finance_dimension
  ADD COLUMN IF NOT EXISTS balancing_sequence INTEGER;

COMMENT ON COLUMN gl.finance_dimension.balancing_sequence IS
  'Ordering of balancing segments: 2=secondary, 3=tertiary.
   Primary balancing segment (Legal Entity) is implicit -- sequence 1.
   NULL when is_balancing=FALSE.
   Maximum 2 secondary balancing segments per COA Structure.';

-- Constraint: balancing_sequence only valid when is_balancing=TRUE
ALTER TABLE gl.finance_dimension
  ADD CONSTRAINT chk_balancing_sequence
  CHECK (
    (is_balancing = FALSE AND balancing_sequence IS NULL) OR
    (is_balancing = TRUE AND balancing_sequence IN (2, 3))
  );

-- Unique constraint: only one dimension per sequence per COA Structure
CREATE UNIQUE INDEX IF NOT EXISTS uq_finance_dimension_balancing_sequence
  ON gl.finance_dimension (coa_structure_id, balancing_sequence)
  WHERE is_balancing = TRUE AND coa_structure_id IS NOT NULL;

-- For Phase 1 Orbinox: all dimensions remain is_balancing=FALSE (default)
-- No data migration needed -- defaults handle it
