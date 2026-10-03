# paste script content here
#!/usr/bin/env bash
#
# eVyoog — Unicon Engineers Pvt Ltd — Complete Demo Seed Script
#
# Creates everything from scratch on a fresh eVyoog instance:
#   1.  Consumption Context (UNICON-CTX)
#   2.  Business Group (BG-UNICON)
#   3.  COA Structure (UNICON-IND-MFG) — 6 segments including Intercompany
#   4.  Ledger (UNICON-PRIM-01, THICK mode, INR)
#   5.  Legal Entity (LE-UNICON-001)
#   6.  Ledger → Legal Entity assignment (PRIMARY)
#   7.  Accounting Calendar (FY 2026-27, April start, Monthly)
#       → Auto-generates 12 regular periods + ADJ-2027
#   8.  UNIT as 2nd Balancing Segment (PROFIT_CENTRE)
#   9.  Business Units: CBE-1 (TN), CBE-2 (TN, shared GSTIN), RYP (CG)
#   10. Dimension values — all 6 dimensions:
#       NAT-ACC: 71 accounts (5 roots, 13 L2, 53 postable leaf)
#       UNIT: CBE-1, CBE-2, RYP
#       COST-CTR: 7 cost centres (defaults per unit)
#       PROJ: GENERAL (default), PROJ-001, PROJ-002
#       INT-CO: empty (reserved for global ops 2028)
#       FUTURE: empty (placeholder)
#   11. Open APR-2026 period
#   12. Import Opening Balances (balanced per unit, ₹2,51,00,000)
#   13. Post 3 demo journals:
#       JE-1: Product Sales CBE-1 (with CGST+SGST)
#       JE-2: Raw Material Purchase RYP (with IGST)
#       JE-3: Salaries CBE-2
#   14. Create Unicon demo user (finance@uniconengineers.com)
#
# Usage:
#   bash scripts/seed_unicon.sh
#
# Env vars:
#   BASE_URL       API base URL (default: http://localhost:8080)
#   ADMIN_EMAIL    login email  (default: admin@evyoog.com)
#   ADMIN_PASSWORD login password (default: Admin@eVyoog1)
#
# Requirements: curl, python3, jq (optional)
#
# Design decisions documented in:
#   docs/Unicon_Engineers_Financial_Design_v2.0.docx
#   TECHNICAL_DEBT.md (DEBT-01 through DEBT-05)

set -euo pipefail

BASE_URL="${BASE_URL:-https://finance-api.evyoog.com}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@evyoog.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin@eVyoog1}"
PGHOST="${PGHOST:-vyg-batch-1.cgtfswn9milw.ap-south-1.rds.amazonaws.com}"
PGPORT="${PGPORT:-5432}"
PGDB="${PGDB:-vygmicroservice}"
PGUSER="${PGUSER:-postgres}"
PGPASSWORD="${PGPASSWORD:-vygpost23}"
PGSSLMODE="${PGSSLMODE:-require}"
export PGPASSWORD PGSSLMODE

# ── helpers ────────────────────────────────────────────────────────────────
ACCESS_TOKEN=""

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }

login() {
  log "Authenticating as $ADMIN_EMAIL..."
  ACCESS_TOKEN=$(curl -sS -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['accessToken'])")
  ok "Token obtained"
}

api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$BASE_URL$path" \
      -H "Authorization: Bearer $ACCESS_TOKEN" \
      -H "X-User-Id: seed-script" \
      -H "Content-Type: application/json" \
      -d "$body"
  else
    curl -sS -X "$method" "$BASE_URL$path" \
      -H "Authorization: Bearer $ACCESS_TOKEN" \
      -H "X-User-Id: seed-script"
  fi
}

extract() {
  python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['$1'])"
}

check_exists() {
  local path="$1" field="${2:-id}"
  local result
  result=$(api GET "$path" 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
items=d.get('data',[])
if isinstance(items,list) and items:
    print(items[0].get('$field',''))
" 2>/dev/null || true)
  echo "$result"
}

pg() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDB" -t -c "$1" | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1
}

# ── idempotency check ──────────────────────────────────────────────────────
check_already_seeded() {
  local count
  count=$(pg "SELECT COUNT(*) FROM gl.legal_entity WHERE code='LE-UNICON-001';" 2>/dev/null || echo "0")
  if [[ "$count" -gt "0" ]]; then
    log "⚠️  Unicon already seeded (LE-UNICON-001 exists). Skipping."
    log "   To reseed: DELETE FROM gl.consumption_context WHERE code='UNICON-CTX';"
    exit 0
  fi
}

# ── Step 1: Consumption Context ────────────────────────────────────────────
create_consumption_context() {
  log "Step 1: Creating Consumption Context..."
  UNICON_CTX_ID=$(pg "
    INSERT INTO gl.consumption_context
      (id, segment_type, code, name, status, is_active,
       created_at, updated_at, created_by, updated_by, provisioning_answers)
    VALUES (
      gen_random_uuid(), 'WORKSPACE', 'UNICON-CTX', 'Unicon Engineers India',
      'ACTIVE', true, NOW(), NOW(), 'SYSTEM', 'system',
      '{\"esMode\":\"THICK_ES\",\"companyName\":\"Unicon Engineers Pvt Ltd\",
        \"businessType\":\"MANUFACTURING\",\"legalStructure\":\"PRIVATE_LIMITED\",
        \"fiscalStartYear\":2026,\"fiscalStartMonth\":4,
        \"accountingStandard\":\"IND_AS\",
        \"states\":[\"TAMIL_NADU\",\"CHHATTISGARH\"]}'::jsonb
    ) RETURNING id;")
  ok "Consumption Context: $UNICON_CTX_ID"
}

# ── Step 2: Business Group ─────────────────────────────────────────────────
create_business_group() {
  log "Step 2: Creating Business Group..."
  UNICON_BG_ID=$(pg "
    INSERT INTO gl.business_group
      (id, consumption_context_id, code, name, es_mode, default_currency,
       is_active, created_at, updated_at, created_by, updated_by)
    VALUES (
      gen_random_uuid(), '$UNICON_CTX_ID', 'BG-UNICON',
      'Unicon Engineers Group', 'THICK_ES', 'INR',
      true, NOW(), NOW(), 'system', 'system'
    ) RETURNING id;")
  ok "Business Group: $UNICON_BG_ID"
}

# ── Step 3: COA Structure (6 segments) ─────────────────────────────────────
create_coa_structure() {
  log "Step 3: Creating COA Structure (6 segments)..."
  login  # refresh token before API calls
  UNICON_COA_ID=$(api POST "/api/v1/gl/coa-structures" "{
    \"businessGroupId\":\"$UNICON_BG_ID\",
    \"code\":\"UNICON-IND-MFG\",
    \"name\":\"Unicon Engineers India COA\",
    \"description\":\"6-segment COA — Indian manufacturing with global expansion provision\",
    \"separator\":\".\",
    \"segments\":[
      {\"code\":\"NAT-ACC\",\"name\":\"Natural Account\",\"dimensionType\":\"NATURAL_ACCOUNT\",\"isRequired\":true,\"segmentNumber\":1},
      {\"code\":\"UNIT\",\"name\":\"Business Unit\",\"dimensionType\":\"CUSTOM\",\"isRequired\":true,\"segmentNumber\":2},
      {\"code\":\"COST-CTR\",\"name\":\"Cost Centre\",\"dimensionType\":\"COST_CENTRE\",\"isRequired\":false,\"segmentNumber\":3},
      {\"code\":\"PROJ\",\"name\":\"Project\",\"dimensionType\":\"PROJECT\",\"isRequired\":false,\"segmentNumber\":4},
      {\"code\":\"INT-CO\",\"name\":\"Intercompany\",\"dimensionType\":\"INTERCOMPANY\",\"isRequired\":false,\"segmentNumber\":5},
      {\"code\":\"FUTURE\",\"name\":\"Future\",\"dimensionType\":\"CUSTOM\",\"isRequired\":false,\"segmentNumber\":6}
    ]
  }" | extract "id")
  ok "COA Structure: $UNICON_COA_ID"

  # Get UNIT dimension ID and set as 2nd balancing segment
  UNIT_DIM_ID=$(pg "SELECT id FROM gl.finance_dimension
    WHERE coa_structure_id='$UNICON_COA_ID' AND code='UNIT';")
  pg "UPDATE gl.finance_dimension
    SET dimension_type='PROFIT_CENTRE', is_balancing=true,
        balancing_sequence=2, updated_at=NOW()
    WHERE id='$UNIT_DIM_ID';" > /dev/null
  ok "UNIT set as PROFIT_CENTRE, 2nd Balancing Segment"
}

# ── Step 4: Ledger ─────────────────────────────────────────────────────────
create_ledger() {
  log "Step 4: Creating Ledger..."
  UNICON_LEDGER_ID=$(api POST "/api/v1/gl/ledgers" "{
    \"coaStructureId\":\"$UNICON_COA_ID\",
    \"code\":\"UNICON-PRIM-01\",
    \"name\":\"Unicon Engineers Primary Ledger\",
    \"financeMode\":\"THICK\",
    \"ledgerCategory\":\"PRIMARY\",
    \"functionalCurrency\":\"INR\",
    \"accountingStandard\":\"IND_AS\",
    \"allowDynamicInsert\":true
  }" | extract "id")
  ok "Ledger: $UNICON_LEDGER_ID"

  # Assign COA to Ledger
  api POST "/api/v1/gl/coa-structures/$UNICON_COA_ID/assign-ledger" \
    "{\"ledgerId\":\"$UNICON_LEDGER_ID\"}" > /dev/null
  ok "COA Structure assigned to Ledger"
}

# ── Step 5: Legal Entity ───────────────────────────────────────────────────
create_legal_entity() {
  log "Step 5: Creating Legal Entity..."
  UNICON_LE_ID=$(api POST "/api/v1/gl/legal-entities" "{
    \"businessGroupId\":\"$UNICON_BG_ID\",
    \"code\":\"LE-UNICON-001\",
    \"name\":\"Unicon Engineers Pvt Ltd\",
    \"accountingStandard\":\"IND_AS\",
    \"tan\":\"CBEC12345B\"
  }" | extract "id")
  ok "Legal Entity: $UNICON_LE_ID"

  # Assign Ledger to Legal Entity
  api POST "/api/v1/gl/legal-entity-ledgers" "{
    \"legalEntityId\":\"$UNICON_LE_ID\",
    \"ledgerId\":\"$UNICON_LEDGER_ID\",
    \"ledgerCategory\":\"PRIMARY\"
  }" > /dev/null
  ok "Ledger assigned to Legal Entity"
}

# ── Step 6: Accounting Calendar ────────────────────────────────────────────
create_calendar() {
  log "Step 6: Creating Accounting Calendar (FY 2026-27)..."
  UNICON_CAL_ID=$(api POST "/api/v1/gl/accounting-calendars" "{
    \"ledgerId\":\"$UNICON_LEDGER_ID\",
    \"name\":\"Unicon FY Calendar\",
    \"description\":\"Indian Fiscal Year April to March — Monthly periods\",
    \"fiscalYearStartMonth\":4,
    \"fiscalYearStartDay\":1,
    \"periodType\":\"MONTHLY\",
    \"initialFiscalYear\":2026
  }" | extract "id")
  ok "Calendar: $UNICON_CAL_ID (12 periods + ADJ-2027 auto-generated)"
}

# ── Step 7: Business Units ─────────────────────────────────────────────────
create_business_units() {
  log "Step 7: Creating Business Units..."
  CBE1_ID=$(api POST "/api/v1/gl/business-units" "{
    \"legalEntityId\":\"$UNICON_LE_ID\",
    \"code\":\"CBE-1\",
    \"name\":\"Coimbatore Unit 1\",
    \"gstin\":\"33AAACU1234A1Z5\",
    \"stateCode\":\"33\"
  }" | extract "id")
  ok "CBE-1: $CBE1_ID"

  CBE2_ID=$(api POST "/api/v1/gl/business-units" "{
    \"legalEntityId\":\"$UNICON_LE_ID\",
    \"code\":\"CBE-2\",
    \"name\":\"Coimbatore Unit 2\",
    \"gstin\":\"33AAACU1234A1Z5\",
    \"stateCode\":\"33\"
  }" | extract "id")
  ok "CBE-2: $CBE2_ID (shared TN GSTIN)"

  RYP_ID=$(api POST "/api/v1/gl/business-units" "{
    \"legalEntityId\":\"$UNICON_LE_ID\",
    \"code\":\"RYP\",
    \"name\":\"Raipur Unit\",
    \"gstin\":\"22AAACU1234A1Z8\",
    \"stateCode\":\"22\"
  }" | extract "id")
  ok "RYP: $RYP_ID (separate CG GSTIN)"
}

# ── Step 8: Open APR-2026 period ───────────────────────────────────────────
open_period() {
  log "Step 8: Opening APR-2026 period..."
  APR_PERIOD_ID=$(pg "SELECT ap.id FROM gl.accounting_period ap
    JOIN gl.accounting_calendar ac ON ac.id=ap.accounting_calendar_id
    WHERE ac.ledger_id='$UNICON_LEDGER_ID' AND ap.name='APR-2026';")

  # Create period status
  PS_ID=$(api POST "/api/v1/gl/period-status" "{
    \"legalEntityId\":\"$UNICON_LE_ID\",
    \"accountingPeriodId\":\"$APR_PERIOD_ID\"
  }" | extract "id")

  # Open it
  api POST "/api/v1/gl/period-status/$PS_ID/open" "" > /dev/null
  ok "APR-2026 OPEN"
}

# ── Step 9: Dimension Values ───────────────────────────────────────────────
create_dimension_values() {
  log "Step 9: Creating dimension values..."

  # Get dimension IDs
  NAT_DIM=$(pg "SELECT id FROM gl.finance_dimension
    WHERE ledger_id='$UNICON_LEDGER_ID' AND code='NAT-ACC';")
  UNIT_DIM=$(pg "SELECT id FROM gl.finance_dimension
    WHERE ledger_id='$UNICON_LEDGER_ID' AND code='UNIT';")
  CC_DIM=$(pg "SELECT id FROM gl.finance_dimension
    WHERE ledger_id='$UNICON_LEDGER_ID' AND code='COST-CTR';")
  PROJ_DIM=$(pg "SELECT id FROM gl.finance_dimension
    WHERE ledger_id='$UNICON_LEDGER_ID' AND code='PROJ';")

  dv() {
    local DIM_ID=$1 CODE=$2 NAME=$3 QUAL=$4 NB=$5 IS_SUM=$6 IS_POST=$7
    local PARENT=${8:-} IS_DEF=${9:-false} GST=${10:-false} TDS=${11:-false}
    local BODY="{\"financeDimensionId\":\"$DIM_ID\",\"code\":\"$CODE\",\"name\":\"$NAME\",\"isSummary\":$IS_SUM,\"isPostable\":$IS_POST,\"isDefault\":$IS_DEF,\"gstApplicable\":$GST,\"tdsApplicable\":$TDS"
    [ -n "$QUAL" ] && BODY="$BODY,\"accountQualifier\":\"$QUAL\""
    [ -n "$NB" ]   && BODY="$BODY,\"normalBalance\":\"$NB\""
    [ -n "$PARENT" ] && BODY="$BODY,\"parentValueId\":\"$PARENT\""
    BODY="$BODY}"
    api POST "/api/v1/gl/dimension-values" "$BODY" | python3 -c \
      "import sys,json; d=json.load(sys.stdin); print(d['data']['id'] if d.get('success') else '')" 2>/dev/null
  }

  # UNIT values
  log "  Creating UNIT values..."
  dv "$UNIT_DIM" "CBE-1" "Coimbatore Unit 1" "" "" "false" "true" "" "false" > /dev/null
  dv "$UNIT_DIM" "CBE-2" "Coimbatore Unit 2" "" "" "false" "true" "" "false" > /dev/null
  dv "$UNIT_DIM" "RYP"   "Raipur Unit"       "" "" "false" "true" "" "false" > /dev/null
  ok "UNIT: CBE-1, CBE-2, RYP"

  # COST-CTR values
  log "  Creating COST-CTR values..."
  dv "$CC_DIM" "CC-CBE1-ADM" "Administration — Coimbatore 1" "" "" "false" "true" "" "true"  > /dev/null
  dv "$CC_DIM" "CC-CBE1-OPS" "Operations — Coimbatore 1"     "" "" "false" "true" "" "false" > /dev/null
  dv "$CC_DIM" "CC-CBE1-MFG" "Manufacturing — Coimbatore 1"  "" "" "false" "true" "" "false" > /dev/null
  dv "$CC_DIM" "CC-CBE2-ADM" "Administration — Coimbatore 2" "" "" "false" "true" "" "false" > /dev/null
  dv "$CC_DIM" "CC-CBE2-OPS" "Operations — Coimbatore 2"     "" "" "false" "true" "" "false" > /dev/null
  dv "$CC_DIM" "CC-RYP-ADM"  "Administration — Raipur"       "" "" "false" "true" "" "false" > /dev/null
  dv "$CC_DIM" "CC-RYP-OPS"  "Operations — Raipur"           "" "" "false" "true" "" "false" > /dev/null
  ok "COST-CTR: 7 cost centres"

  # PROJ values
  log "  Creating PROJ values..."
  dv "$PROJ_DIM" "GENERAL"  "General / No Project" "" "" "false" "true" "" "true"  > /dev/null
  dv "$PROJ_DIM" "PROJ-001" "Demo Project 1"       "" "" "false" "true" "" "false" > /dev/null
  dv "$PROJ_DIM" "PROJ-002" "Demo Project 2"       "" "" "false" "true" "" "false" > /dev/null
  ok "PROJ: GENERAL (default), PROJ-001, PROJ-002"

  # NAT-ACC — Chart of Accounts (71 accounts, 3-level hierarchy)
  log "  Creating Chart of Accounts (71 accounts)..."
  python3 << PYEOF
import requests, sys

BASE = "$BASE_URL"
TOKEN = "$ACCESS_TOKEN"
NAT_DIM = "$NAT_DIM"
HDR = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}

def dv(code, name, qual, nb, summary, postable, parent=None, gst=False, tds=False, tds_sec=None, default=False):
    body = {"financeDimensionId": NAT_DIM, "code": code, "name": name,
            "accountQualifier": qual, "normalBalance": nb,
            "isSummary": summary, "isPostable": postable,
            "isDefault": default, "gstApplicable": gst, "tdsApplicable": tds}
    if parent: body["parentValueId"] = parent
    if tds_sec: body["tdsSection"] = tds_sec
    r = requests.post(f"{BASE}/api/v1/gl/dimension-values", headers=HDR, json=body)
    d = r.json()
    if d.get("success"): return d["data"]["id"]
    print(f"  ❌ {code}: {d.get('message','?')}", file=sys.stderr)
    return None

# Roots
A1000 = dv("1000","Total Assets",      "ASSET",    "DR",True, False)
L2000 = dv("2000","Total Liabilities", "LIABILITY","CR",True, False)
E3000 = dv("3000","Total Equity",      "EQUITY",   "CR",True, False)
R4000 = dv("4000","Total Revenue",     "REVENUE",  "CR",True, False)
X5000 = dv("5000","Total Expenses",    "EXPENSE",  "DR",True, False)

# Asset L2
A1100 = dv("1100","Current Assets",   "ASSET","DR",True, False, A1000)
A1200 = dv("1200","Bank Accounts",    "ASSET","DR",True, False, A1100)
A1300 = dv("1300","Sundry Debtors",   "ASSET","DR",True, False, A1100)
A1400 = dv("1400","Stock-in-Hand",    "ASSET","DR",True, False, A1100)
A1500 = dv("1500","Loans & Advances", "ASSET","DR",True, False, A1100)
A1700 = dv("1700","Fixed Assets",     "ASSET","DR",True, False, A1000)

# Asset L3
dv("1110","Cash in Hand",           "ASSET","DR",False,True, A1100)
dv("1210","HDFC Bank Current A/c",  "ASSET","DR",False,True, A1200)
dv("1220","Bank of Baroda",         "ASSET","DR",False,True, A1200)
dv("1310","Domestic Debtors",       "ASSET","DR",False,True, A1300)
dv("1320","Export Debtors",         "ASSET","DR",False,True, A1300)
dv("1410","Raw Material Stock",     "ASSET","DR",False,True, A1400)
dv("1420","Finished Goods Stock",   "ASSET","DR",False,True, A1400)
dv("1510","Advance to Suppliers",   "ASSET","DR",False,True, A1500)
dv("1520","Security Deposits",      "ASSET","DR",False,True, A1500)
dv("1610","IGST Input Tax Credit",  "ASSET","DR",False,True, A1100, gst=True)
dv("1620","CGST Input Tax Credit",  "ASSET","DR",False,True, A1100, gst=True)
dv("1630","SGST Input Tax Credit",  "ASSET","DR",False,True, A1100, gst=True)
dv("1710","Plant & Machinery",      "ASSET","DR",False,True, A1700)
dv("1720","Computers & Software",   "ASSET","DR",False,True, A1700)
dv("1730","Furniture & Fixtures",   "ASSET","DR",False,True, A1700)
dv("1740","Accumulated Depreciation","ASSET","CR",False,True, A1700)
dv("1850","Due From Other Units",   "ASSET","DR",False,True, A1000)

# Liability L2
L2100 = dv("2100","Sundry Creditors",  "LIABILITY","CR",True, False, L2000)
L2200 = dv("2200","Duties & Taxes",    "LIABILITY","CR",True, False, L2000)
L2300 = dv("2300","Provisions",        "LIABILITY","CR",True, False, L2000)
L2400 = dv("2400","Loans (Liability)", "LIABILITY","CR",True, False, L2000)

# Liability L3
dv("2110","Component Suppliers", "LIABILITY","CR",False,True, L2100)
dv("2120","Service Suppliers",   "LIABILITY","CR",False,True, L2100)
dv("2130","Transporters",        "LIABILITY","CR",False,True, L2100)
dv("2210","IGST Payable",        "LIABILITY","CR",False,True, L2200, gst=True)
dv("2220","CGST Payable",        "LIABILITY","CR",False,True, L2200, gst=True)
dv("2230","SGST Payable",        "LIABILITY","CR",False,True, L2200, gst=True)
dv("2240","TDS Payable",         "LIABILITY","CR",False,True, L2200, tds=True)
dv("2310","Salary Payable",      "LIABILITY","CR",False,True, L2300)
dv("2320","Expenses Payable",    "LIABILITY","CR",False,True, L2300)
dv("2410","Bank Overdraft",      "LIABILITY","CR",False,True, L2400)
dv("2550","Due To Other Units",  "LIABILITY","CR",False,True, L2000)

# Equity
E3100 = dv("3100","Capital Account",    "EQUITY","CR",True, False, E3000)
E3200 = dv("3200","Reserves & Surplus", "EQUITY","CR",True, False, E3000)
dv("3110","Share Capital",     "EQUITY","CR",False,True, E3100)
dv("3210","General Reserve",   "EQUITY","CR",False,True, E3200)
dv("3220","Profit & Loss A/c", "EQUITY","CR",False,True, E3200)

# Revenue
R4100 = dv("4100","Sales — Domestic", "REVENUE","CR",True, False, R4000)
R4200 = dv("4200","Sales — Export",   "REVENUE","CR",True, False, R4000)
R4300 = dv("4300","Other Income",     "REVENUE","CR",True, False, R4000)
dv("4110","Product Sales",        "REVENUE","CR",False,True, R4100, gst=True)
dv("4120","Service Revenue",      "REVENUE","CR",False,True, R4100, gst=True)
dv("4210","Export Sales",         "REVENUE","CR",False,True, R4200)
dv("4310","Interest Income",      "REVENUE","CR",False,True, R4300)
dv("4320","Miscellaneous Income", "REVENUE","CR",False,True, R4300)

# Expense
X5100 = dv("5100","Purchase Accounts", "EXPENSE","DR",True, False, X5000)
X5200 = dv("5200","Direct Expenses",   "EXPENSE","DR",True, False, X5000)
X5300 = dv("5300","Indirect Expenses", "EXPENSE","DR",True, False, X5000)
dv("5110","Raw Material Purchase",  "EXPENSE","DR",False,True, X5100, gst=True)
dv("5120","Consumables Purchase",   "EXPENSE","DR",False,True, X5100)
dv("5210","Direct Labour",          "EXPENSE","DR",False,True, X5200)
dv("5220","Factory Overhead",       "EXPENSE","DR",False,True, X5200)
dv("5230","Power & Fuel",           "EXPENSE","DR",False,True, X5200)
dv("5310","Salaries & Staff Cost",  "EXPENSE","DR",False,True, X5300)
dv("5320","Rent",                   "EXPENSE","DR",False,True, X5300, tds=True, tds_sec="194I")
dv("5330","Professional Fees",      "EXPENSE","DR",False,True, X5300, tds=True, tds_sec="194J")
dv("5340","Freight & Transport",    "EXPENSE","DR",False,True, X5300, tds=True, tds_sec="194C")
dv("5350","Depreciation",           "EXPENSE","DR",False,True, X5300)
dv("5360","Administrative Expenses","EXPENSE","DR",False,True, X5300)
dv("5370","Marketing Expenses",     "EXPENSE","DR",False,True, X5300)

print("  71 accounts created")
PYEOF
  ok "NAT-ACC: 71 accounts (3-level hierarchy)"
}

# ── Step 10: Opening Balances ──────────────────────────────────────────────
import_opening_balances() {
  log "Step 10: Importing Opening Balances (₹2,51,00,000 balanced per unit)..."

  APR_STATUS_ID=$(pg "SELECT ps.id FROM gl.period_status ps
    JOIN gl.accounting_period ap ON ap.id=ps.accounting_period_id
    WHERE ps.legal_entity_id='$UNICON_LE_ID' AND ap.name='APR-2026';")

  # Generate OB file
  python3 << PYEOF
import openpyxl
wb = openpyxl.Workbook()
ws = wb.active
ws.title = "Opening Balances"
headers = ["accountCode","UNIT","COST-CTR","PROJ","INT-CO","FUTURE","balance","description"]
for col, h in enumerate(headers, 1):
    ws.cell(row=1, column=col, value=h)
rows = [
  ["1210","CBE-1","CC-CBE1-ADM","GENERAL","","", 4500000, "HDFC Bank — CBE-1"],
  ["1210","CBE-2","CC-CBE2-ADM","GENERAL","","", 3000000, "HDFC Bank — CBE-2"],
  ["1210","RYP",  "CC-RYP-ADM", "GENERAL","","", 1200000, "HDFC Bank — RYP"],
  ["1310","CBE-1","CC-CBE1-ADM","GENERAL","","", 2800000, "Domestic Debtors — CBE-1"],
  ["1310","CBE-2","CC-CBE2-ADM","GENERAL","","", 1800000, "Domestic Debtors — CBE-2"],
  ["1310","RYP",  "CC-RYP-ADM", "GENERAL","","",  800000, "Domestic Debtors — RYP"],
  ["1410","CBE-1","CC-CBE1-MFG","GENERAL","","", 1500000, "Raw Material — CBE-1"],
  ["1410","CBE-2","CC-CBE2-OPS","GENERAL","","", 1000000, "Raw Material — CBE-2"],
  ["1410","RYP",  "CC-RYP-OPS", "GENERAL","","",  500000, "Raw Material — RYP"],
  ["1710","CBE-1","CC-CBE1-MFG","GENERAL","","", 5500000, "Plant & Machinery — CBE-1"],
  ["1710","RYP",  "CC-RYP-OPS", "GENERAL","","", 2500000, "Plant & Machinery — RYP"],
  ["2110","CBE-1","CC-CBE1-ADM","GENERAL","","", 1200000, "Component Suppliers — CBE-1"],
  ["2110","CBE-2","CC-CBE2-ADM","GENERAL","","",  800000, "Component Suppliers — CBE-2"],
  ["2110","RYP",  "CC-RYP-ADM", "GENERAL","","",  400000, "Component Suppliers — RYP"],
  ["2310","CBE-1","CC-CBE1-ADM","GENERAL","","",  350000, "Salary Payable — CBE-1"],
  ["2310","CBE-2","CC-CBE2-ADM","GENERAL","","",  200000, "Salary Payable — CBE-2"],
  ["2310","RYP",  "CC-RYP-ADM", "GENERAL","","",  180000, "Salary Payable — RYP"],
  ["3110","CBE-1","CC-CBE1-ADM","GENERAL","","", 8000000, "Share Capital — CBE-1"],
  ["3110","CBE-2","CC-CBE2-ADM","GENERAL","","", 3000000, "Share Capital — CBE-2"],
  ["3110","RYP",  "CC-RYP-ADM", "GENERAL","","", 2000000, "Share Capital — RYP"],
  ["3220","CBE-1","CC-CBE1-ADM","GENERAL","","", 4750000, "P&L A/c — CBE-1"],
  ["3220","CBE-2","CC-CBE2-ADM","GENERAL","","", 1800000, "P&L A/c — CBE-2"],
  ["3220","RYP",  "CC-RYP-ADM", "GENERAL","","", 2420000, "P&L A/c — RYP"],
]
for r_idx, row in enumerate(rows, 2):
    for c_idx, val in enumerate(row, 1):
        ws.cell(row=r_idx, column=c_idx, value=val)
wb.save("/tmp/unicon_ob.xlsx")
print("OB file ready")
PYEOF

  RESULT=$(curl -sS -X POST \
    "$BASE_URL/api/v1/gl/opening-balances/import?legalEntityId=$UNICON_LE_ID&ledgerId=$UNICON_LEDGER_ID&accountingPeriodId=$APR_STATUS_ID&createdBy=seed-script" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "X-User-Id: seed-script" \
    -F "file=@/tmp/unicon_ob.xlsx" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data'].get('postedLines',0), d['data'].get('journalCount',0))")
  ok "Opening Balances: $RESULT lines posted across 3 unit journals"
}

# ── Step 11: Demo Journals ─────────────────────────────────────────────────
post_demo_journals() {
  log "Step 11: Posting 3 demo journals..."

  # Get required IDs
  APR_STATUS_ID=$(pg "SELECT ps.id FROM gl.period_status ps
    JOIN gl.accounting_period ap ON ap.id=ps.accounting_period_id
    WHERE ps.legal_entity_id='$UNICON_LE_ID' AND ap.name='APR-2026';")
  MANUAL_SRC=$(pg "SELECT id FROM gl.journal_source
    WHERE ledger_id='$UNICON_LEDGER_ID' AND code='MANUAL';")
  SALES_CAT=$(pg "SELECT id FROM gl.journal_category
    WHERE ledger_id='$UNICON_LEDGER_ID' AND code='SALES';")
  PURCH_CAT=$(pg "SELECT id FROM gl.journal_category
    WHERE ledger_id='$UNICON_LEDGER_ID' AND code='PURCHASE';")
  PAYROLL_CAT=$(pg "SELECT id FROM gl.journal_category
    WHERE ledger_id='$UNICON_LEDGER_ID' AND code='PAYROLL';")

  get_acct() {
    pg "SELECT id FROM gl.dimension_value
      WHERE finance_dimension_id='$NAT_DIM' AND code='$1';"
  }

  NAT_DIM=$(pg "SELECT id FROM gl.finance_dimension
    WHERE ledger_id='$UNICON_LEDGER_ID' AND code='NAT-ACC';")

  A1210=$(get_acct "1210"); A1610=$(get_acct "1610")
  L2110=$(get_acct "2110"); L2220=$(get_acct "2220")
  L2230=$(get_acct "2230"); L2310=$(get_acct "2310")
  R4110=$(get_acct "4110"); X5110=$(get_acct "5110")
  X5310=$(get_acct "5310")

  post_journal() {
    local DESC=$1 SRC=$2 CAT=$3 LINES=$4
    api POST "/api/v1/gl/journals" "{
      \"ledgerId\":\"$UNICON_LEDGER_ID\",
      \"legalEntityId\":\"$UNICON_LE_ID\",
      \"accountingPeriodId\":\"$APR_STATUS_ID\",
      \"journalSourceId\":\"$SRC\",
      \"journalCategoryId\":\"$CAT\",
      \"glDate\":\"2026-04-30\",
      \"description\":\"$DESC\",
      \"lines\":$LINES
    }" | python3 -c \
      "import sys,json; d=json.load(sys.stdin); print(d['data'].get('journalNumber','ERROR'))" 2>/dev/null
  }

  # Journal 1 — Sales CBE-1
  JE1=$(post_journal "Product Sales — CBE-1 April 2026" "$MANUAL_SRC" "$SALES_CAT" "[
    {\"naturalAccountValueId\":\"$A1210\",\"accountCombination\":{\"NATURAL_ACCOUNT\":\"1210\",\"PROFIT_CENTRE\":\"CBE-1\",\"COST_CENTRE\":\"CC-CBE1-OPS\",\"PROJECT\":\"GENERAL\"},\"debitAmount\":1180000,\"description\":\"HDFC Bank — Sales receipt\"},
    {\"naturalAccountValueId\":\"$R4110\",\"accountCombination\":{\"NATURAL_ACCOUNT\":\"4110\",\"PROFIT_CENTRE\":\"CBE-1\",\"COST_CENTRE\":\"CC-CBE1-OPS\",\"PROJECT\":\"GENERAL\"},\"creditAmount\":1000000,\"description\":\"Product Sales\"},
    {\"naturalAccountValueId\":\"$L2220\",\"accountCombination\":{\"NATURAL_ACCOUNT\":\"2220\",\"PROFIT_CENTRE\":\"CBE-1\",\"COST_CENTRE\":\"CC-CBE1-ADM\",\"PROJECT\":\"GENERAL\"},\"creditAmount\":90000,\"description\":\"CGST Payable 9%\"},
    {\"naturalAccountValueId\":\"$L2230\",\"accountCombination\":{\"NATURAL_ACCOUNT\":\"2230\",\"PROFIT_CENTRE\":\"CBE-1\",\"COST_CENTRE\":\"CC-CBE1-ADM\",\"PROJECT\":\"GENERAL\"},\"creditAmount\":90000,\"description\":\"SGST Payable 9%\"}
  ]")
  ok "Journal 1: $JE1 — Product Sales CBE-1 ₹11,80,000"

  # Journal 2 — RM Purchase RYP
  JE2=$(post_journal "Raw Material Purchase — RYP April 2026" "$MANUAL_SRC" "$PURCH_CAT" "[
    {\"naturalAccountValueId\":\"$X5110\",\"accountCombination\":{\"NATURAL_ACCOUNT\":\"5110\",\"PROFIT_CENTRE\":\"RYP\",\"COST_CENTRE\":\"CC-RYP-OPS\",\"PROJECT\":\"GENERAL\"},\"debitAmount\":500000,\"description\":\"Raw Material Purchase RYP\"},
    {\"naturalAccountValueId\":\"$A1610\",\"accountCombination\":{\"NATURAL_ACCOUNT\":\"1610\",\"PROFIT_CENTRE\":\"RYP\",\"COST_CENTRE\":\"CC-RYP-OPS\",\"PROJECT\":\"GENERAL\"},\"debitAmount\":90000,\"description\":\"IGST Input Tax Credit 18%\"},
    {\"naturalAccountValueId\":\"$L2110\",\"accountCombination\":{\"NATURAL_ACCOUNT\":\"2110\",\"PROFIT_CENTRE\":\"RYP\",\"COST_CENTRE\":\"CC-RYP-ADM\",\"PROJECT\":\"GENERAL\"},\"creditAmount\":590000,\"description\":\"Component Suppliers Payable RYP\"}
  ]")
  ok "Journal 2: $JE2 — Raw Material Purchase RYP ₹5,90,000"

  # Journal 3 — Salaries CBE-2
  JE3=$(post_journal "Salaries — CBE-2 April 2026" "$MANUAL_SRC" "$PAYROLL_CAT" "[
    {\"naturalAccountValueId\":\"$X5310\",\"accountCombination\":{\"NATURAL_ACCOUNT\":\"5310\",\"PROFIT_CENTRE\":\"CBE-2\",\"COST_CENTRE\":\"CC-CBE2-OPS\",\"PROJECT\":\"GENERAL\"},\"debitAmount\":350000,\"description\":\"Salaries CBE-2 April 2026\"},
    {\"naturalAccountValueId\":\"$L2310\",\"accountCombination\":{\"NATURAL_ACCOUNT\":\"2310\",\"PROFIT_CENTRE\":\"CBE-2\",\"COST_CENTRE\":\"CC-CBE2-ADM\",\"PROJECT\":\"GENERAL\"},\"creditAmount\":350000,\"description\":\"Salary Payable CBE-2 April 2026\"}
  ]")
  ok "Journal 3: $JE3 — Salaries CBE-2 ₹3,50,000"
}

# ── Step 12: Demo User ─────────────────────────────────────────────────────
create_demo_user() {
  log "Step 12: Creating Unicon demo user..."
  GL_MANAGER_ROLE=$(pg "SELECT id FROM auth.roles WHERE code='GL_MANAGER';")

  USER_ID=$(api POST "/api/v1/auth/users" "{
    \"email\":\"finance@uniconengineers.com\",
    \"fullName\":\"Unicon Finance Manager\",
    \"password\":\"Unicon@2026\",
    \"legalEntityId\":\"$UNICON_LE_ID\",
    \"roleId\":\"$GL_MANAGER_ROLE\"
  }" | extract "id")

  # Disable must-change-password
  pg "UPDATE auth.users SET must_change_pwd=false, updated_at=NOW()
    WHERE id='$USER_ID';" > /dev/null

  ok "Demo user: finance@uniconengineers.com / Unicon@2026"
}

# ── Main ───────────────────────────────────────────────────────────────────
main() {
  echo ""
  echo "════════════════════════════════════════════════════════"
  echo "  eVyoog — Unicon Engineers Demo Seed"
  echo "════════════════════════════════════════════════════════"
  echo ""

  check_already_seeded
  login
  create_consumption_context
  create_business_group
  --create_coa_structure
  --create_ledger
  --create_legal_entity
  --create_calendar
  --create_business_units
  --open_period
  --create_dimension_values
  --import_opening_balances
  --post_demo_journals
  create_demo_user

  echo ""
  echo "════════════════════════════════════════════════════════"
  echo "  ✅ Unicon Demo Seed Complete!"
  echo "════════════════════════════════════════════════════════"
  echo ""
  echo "  Demo URL:   http://localhost:5173"
  echo "  Email:      finance@uniconengineers.com"
  echo "  Password:   Unicon@2026"
  echo ""
  echo "  Legal Entity: $UNICON_LE_ID"
  echo "  Ledger:       $UNICON_LEDGER_ID"
  echo "════════════════════════════════════════════════════════"
}

main "$@"
