-- DEBT-01 short-term fix: add SPARE to DimensionType.
-- account_combination's JSONB key is DimensionType.name(), so two CUSTOM
-- dimensions on one Ledger collide. SPARE gives a second customer-defined
-- slot (e.g. UNIT=CUSTOM, FUTURE=SPARE). The V4 CHECK constraint enumerates
-- the allowed values, so it must be widened or inserting SPARE fails.
ALTER TABLE gl.finance_dimension DROP CONSTRAINT IF EXISTS finance_dimension_dimension_type_check;
ALTER TABLE gl.finance_dimension ADD CONSTRAINT finance_dimension_dimension_type_check
    CHECK (dimension_type IN (
        'LEGAL_ENTITY', 'NATURAL_ACCOUNT', 'COST_CENTRE',
        'PROFIT_CENTRE', 'INTERCOMPANY', 'PRODUCT', 'PROJECT', 'CUSTOM', 'SPARE'
    ));
