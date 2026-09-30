-- ============================================================================
-- V34: Correct Adjustment Period fiscal-year selection (fixes a V33 bug)
--
-- V33 picked each calendar's LATEST fiscal year (DISTINCT ON ... ORDER BY
-- end_date DESC) for its auto-generated Adjustment Period. That is wrong
-- whenever a calendar already has more than one fiscal year generated (e.g.
-- the next year was pre-generated ahead of time) — the Adjustment Period
-- belongs to the EARLIEST/current fiscal year being closed, not a future
-- one already sitting pre-generated ahead of it.
--
-- Confirmed against live data: the Unicon calendar spans FY2026-27 and
-- FY2027-28. V33 wrongly added ADJ-2028 (after MAR-2028, fiscal_year
-- '2027-28') instead of ADJ-2027 (after MAR-2027, fiscal_year '2026-27').
--
-- V33 itself is left untouched — it already ran against the live dev
-- database and any other environment that applied it; editing its SQL now
-- would change its checksum and break Flyway validation there. This
-- migration is a pure data fix, safe to run whether or not V33 has already
-- added anything: it deletes whichever ADJUSTMENT period does not belong
-- to each calendar's earliest fiscal year, then inserts the correct one if
-- missing. On a fresh database with no periods yet (e.g. Testcontainers,
-- where Legal Entities/Calendars are created via the API after migrations
-- run) both statements affect zero rows.
--
-- Going forward, new calendars no longer depend on this kind of migration
-- backfill at all: AccountingPeriodService.generateAdjustmentPeriod() is
-- now called from AccountingCalendarService.create() right after a new
-- calendar's first fiscal year is generated.
-- ============================================================================

-- Step 1: delete any ADJUSTMENT period that does not belong to its
-- calendar's earliest fiscal year (i.e. whatever V33's buggy "latest fiscal
-- year" logic wrongly added).
WITH fy_ends AS (
    SELECT accounting_calendar_id, fiscal_year, MAX(end_date) AS fy_end_date
    FROM gl.accounting_period
    WHERE period_type = 'REGULAR'
    GROUP BY accounting_calendar_id, fiscal_year
),
correct_fy AS (
    SELECT DISTINCT ON (accounting_calendar_id)
        accounting_calendar_id, fiscal_year, fy_end_date
    FROM fy_ends
    ORDER BY accounting_calendar_id, fy_end_date ASC
)
DELETE FROM gl.accounting_period ap
USING correct_fy cf
WHERE ap.accounting_calendar_id = cf.accounting_calendar_id
  AND ap.period_type = 'ADJUSTMENT'
  AND ap.fiscal_year <> cf.fiscal_year;

-- Step 2: insert the correct Adjustment Period for each calendar's earliest
-- fiscal year, if it doesn't already exist (and if period 13 isn't already
-- taken by a REGULAR period, e.g. a FISCAL_4_4_5 calendar).
WITH fy_ends AS (
    SELECT accounting_calendar_id, fiscal_year, MAX(end_date) AS fy_end_date
    FROM gl.accounting_period
    WHERE period_type = 'REGULAR'
    GROUP BY accounting_calendar_id, fiscal_year
),
correct_fy AS (
    SELECT DISTINCT ON (accounting_calendar_id)
        accounting_calendar_id, fiscal_year, fy_end_date
    FROM fy_ends
    ORDER BY accounting_calendar_id, fy_end_date ASC
)
INSERT INTO gl.accounting_period
    (id, accounting_calendar_id, name, period_number, fiscal_year, period_type,
     quarter_number, start_date, end_date, created_at, updated_at, created_by, updated_by)
SELECT
    uuid_generate_v4(),
    cf.accounting_calendar_id,
    'ADJ-' || EXTRACT(YEAR FROM cf.fy_end_date)::text,
    13,
    cf.fiscal_year,
    'ADJUSTMENT',
    4,
    cf.fy_end_date + INTERVAL '1 day',
    cf.fy_end_date + INTERVAL '15 day',
    now(), now(), 'system', 'system'
FROM correct_fy cf
WHERE NOT EXISTS (
    SELECT 1 FROM gl.accounting_period ap2
    WHERE ap2.accounting_calendar_id = cf.accounting_calendar_id
      AND ap2.fiscal_year = cf.fiscal_year
      AND ap2.period_type = 'ADJUSTMENT'
)
AND NOT EXISTS (
    SELECT 1 FROM gl.accounting_period ap3
    WHERE ap3.accounting_calendar_id = cf.accounting_calendar_id
      AND ap3.period_number = 13
      AND ap3.period_type = 'REGULAR'
);
