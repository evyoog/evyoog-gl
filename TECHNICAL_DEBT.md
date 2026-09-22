# eVyoog GL — Technical Debt Register

> Maintained by: eVyoog Engineering Team
> Last updated: September 2026

---

## DEBT-01 — DimensionType collision: UNIT uses PROFIT_CENTRE as workaround

**Priority:** 🔴 Critical | **Status:** OPEN — workaround in place | **Logged:** Sep 2026

### Root Cause
account_combination JSONB uses DimensionType.name() as key. Two CUSTOM dimensions
silently overwrite each other. UNIT and FUTURE both CUSTOM → collision.

### What Was Done
- V32: FinanceDimensionService rejects duplicate DimensionType per Ledger (409)
- Workaround: UNIT changed CUSTOM → PROFIT_CENTRE to avoid collision

### Short-Term Fix (Phase 1 stabilisation)
Add SPARE to DimensionType enum. UNIT stays CUSTOM, FUTURE becomes SPARE.
No migration needed. Effort: 2 hours.

### Long-Term Fix (Phase 2)
Migrate account_combination JSONB keys from DimensionType.name() → dimension.code.
Breaking change — affects PostingEngine, AccountCombinationService, TrialBalance,
SegmentReporting, HierarchicalTrialBalance, AIE/OB parsers, GIN indexes,
journal_line + account_balance backfill. Effort: 3 days. Risk: HIGH.

---

## DEBT-02 — GSTIN uniqueness removed at DB level

**Priority:** 🟡 Important | **Status:** OPEN — acceptable risk | **Logged:** Sep 2026

### Root Cause
CBE-1 and CBE-2 share Tamil Nadu GSTIN. DB unique constraint blocked this.

### What Was Done
V32: Dropped business_unit_gstin_key constraint. Service now validates GSTIN
uniqueness across different LEs only. GSTIN uniqueness enforced by GST system externally.

---

## DEBT-03 — account_combination JSONB key = DimensionType.name() (CRITICAL)

**Priority:** 🔴 Critical | **Status:** OPEN — not yet fixed | **Logged:** Sep 2026

### Root Cause
All GL services use DimensionType.name() as JSONB key — breaks with 2+ dims of same type.

### Correct Fix (Phase 2)
Change all services to use dimension.code as JSONB key:
  {"NATURAL_ACCOUNT":"1210"} → {"NAT-ACC":"1210"}
  {"CUSTOM":"CBE-1"}         → {"UNIT":"CBE-1"}

Affected: PostingEngine, AccountCombinationService, TrialBalanceService,
SegmentReportingService, HierarchicalTrialBalanceService, ExcelParserService,
OpeningBalanceService, GIN indexes. Effort: 3 days. Must fix before Phase 2.

---

## DEBT-04 — display_order vs segment_number inconsistency

**Priority:** 🟢 Nice-to-have | **Status:** OPEN | **Logged:** Sep 2026

API accepts segmentNumber but stores as display_order. Standardise on
segment_number throughout. Effort: 2 hours.

---

## DEBT-05 — No idempotency in customer configuration seed scripts

**Priority:** 🟡 Important | **Status:** OPEN | **Logged:** Sep 2026

unicon_seed.sh has no check-before-create pattern — fails if run twice.
Add idempotency checks to all seed scripts. Effort: 2 hours per script.

---

## Resolved

| ID | Description | Resolution | Date |
|---|---|---|---|
| — | seed-demo-data.sh not idempotent | Fixed — check-before-create | Aug 2026 |
| — | JE-11 closing entry in seed data | Removed — deferred capability | Aug 2026 |
