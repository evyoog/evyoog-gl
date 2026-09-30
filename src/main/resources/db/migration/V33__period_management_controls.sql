-- ============================================================================
-- V33: Period Management Controls
--
-- NOTE on the build prompt's step 2 ("new column gl.accounting_period.period_type"):
-- period_type already exists on gl.accounting_period since the V7 baseline
-- (GL-09), with CHECK (period_type IN ('REGULAR','ADJUSTMENT','YEAR_END')).
-- No column added here — see CLAUDE.md deviation note.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Step 1: gl.legal_entity_period_config — one row per Legal Entity
-- ----------------------------------------------------------------------------
CREATE TABLE gl.legal_entity_period_config (
    id                          UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    legal_entity_id             UUID            NOT NULL UNIQUE REFERENCES gl.legal_entity(id),
    max_open_periods            INTEGER         NOT NULL DEFAULT 2
                                    CHECK (max_open_periods > 0),
    future_period_limit         INTEGER         NOT NULL DEFAULT 1
                                    CHECK (future_period_limit BETWEEN 0 AND 3),
    adjustment_period_enabled   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100)    NOT NULL DEFAULT 'system',
    updated_by                  VARCHAR(100)    NOT NULL DEFAULT 'system'
);

CREATE INDEX idx_le_period_config_legal_entity
    ON gl.legal_entity_period_config (legal_entity_id);

COMMENT ON TABLE gl.legal_entity_period_config IS
    'Per-Legal-Entity Period Management configuration: max concurrently open
     periods, how many periods ahead of the current one may be opened, and
     whether the fiscal-year Adjustment Period is enabled.';

-- ----------------------------------------------------------------------------
-- Step 2: gl.period_status.status — add PERMANENTLY_CLOSED
-- ----------------------------------------------------------------------------
ALTER TABLE gl.period_status DROP CONSTRAINT IF EXISTS period_status_status_check;
ALTER TABLE gl.period_status ADD CONSTRAINT period_status_status_check
    CHECK (status IN (
        'NOT_OPENED',
        'FUTURE_ENTERABLE',
        'OPEN',
        'CLOSED',
        'LOCKED',
        'PERMANENTLY_CLOSED'
    ));

-- ----------------------------------------------------------------------------
-- Step 3: seed default config for every existing Legal Entity
-- ----------------------------------------------------------------------------
INSERT INTO gl.legal_entity_period_config
    (id, legal_entity_id, max_open_periods, future_period_limit,
     adjustment_period_enabled, created_at, updated_at, created_by, updated_by)
SELECT uuid_generate_v4(), le.id, 2, 1, TRUE, now(), now(), 'system', 'system'
FROM gl.legal_entity le
ON CONFLICT (legal_entity_id) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Step 4: add an Adjustment Period (period 13) to every existing calendar's
-- latest fiscal year — 2-week window right after that fiscal year's last
-- REGULAR period. Skipped for any calendar whose period_number 13 is already
-- taken (e.g. a FISCAL_4_4_5 calendar, which already runs 13 regular periods)
-- and for any fiscal year that already has an ADJUSTMENT period.
-- ----------------------------------------------------------------------------
INSERT INTO gl.accounting_period
    (id, accounting_calendar_id, name, period_number, fiscal_year, period_type,
     quarter_number, start_date, end_date, created_at, updated_at, created_by, updated_by)
SELECT
    uuid_generate_v4(),
    fy.accounting_calendar_id,
    'ADJ-' || EXTRACT(YEAR FROM fy.max_end_date)::text,
    13,
    fy.fiscal_year,
    'ADJUSTMENT',
    4,
    fy.max_end_date + INTERVAL '1 day',
    fy.max_end_date + INTERVAL '15 day',
    now(), now(), 'system', 'system'
FROM (
    SELECT DISTINCT ON (accounting_calendar_id)
        accounting_calendar_id,
        fiscal_year,
        MAX(end_date) OVER (PARTITION BY accounting_calendar_id) AS max_end_date
    FROM gl.accounting_period
    WHERE period_type = 'REGULAR'
    ORDER BY accounting_calendar_id, end_date DESC
) fy
WHERE NOT EXISTS (
    SELECT 1 FROM gl.accounting_period ap2
    WHERE ap2.accounting_calendar_id = fy.accounting_calendar_id
      AND ap2.fiscal_year = fy.fiscal_year
      AND ap2.period_type = 'ADJUSTMENT'
)
AND NOT EXISTS (
    SELECT 1 FROM gl.accounting_period ap3
    WHERE ap3.accounting_calendar_id = fy.accounting_calendar_id
      AND ap3.period_number = 13
);
