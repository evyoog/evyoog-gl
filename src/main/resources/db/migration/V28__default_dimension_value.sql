-- ============================================================
-- V28: Default Dimension Value
-- Allow one dimension value per finance dimension to be
-- marked as the default for optional dimensions.
-- ============================================================

-- Add is_default column to gl.dimension_value
ALTER TABLE gl.dimension_value
  ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN gl.dimension_value.is_default IS
  'When TRUE, this value is auto-inserted into account_combination
   for optional dimensions when no value is explicitly selected.
   Only one value per finance_dimension may be marked is_default=TRUE.';

-- Unique partial index: at most one default per dimension
-- (only enforces uniqueness when is_default=TRUE)
CREATE UNIQUE INDEX IF NOT EXISTS uq_dimension_value_default
  ON gl.dimension_value (finance_dimension_id)
  WHERE is_default = TRUE;
