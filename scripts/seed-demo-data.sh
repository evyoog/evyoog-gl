#!/usr/bin/env bash
#
# Seeds a realistic demo dataset for "Orbinox Valves India Pvt Ltd" under a
# Legal Entity produced by the Setup Wizard:
#   1. Ledger (THICK mode) linked to the Legal Entity
#   2. NATURAL_ACCOUNT Finance Dimension (prerequisite for Chart of Accounts)
#   3. Accounting Calendar (auto-generates FY 2025-26 periods on creation)
#   4. Opens the APR-2025 period for the Legal Entity
#   5. 25 Chart of Accounts entries (Assets/Liabilities/Equity/Revenue/Expense)
#   6. Cost Centre (required) and Product (optional) Finance Dimensions + values
#   7. 10 posted journal entries covering opening balances, sales, purchases,
#      payroll, and depreciation — each line carries COST_CENTRE (and PRODUCT
#      on revenue lines) in its account combination
#   8. Closes the APR-2025 period
#
# Usage:
#   ./scripts/seed-demo-data.sh <legalEntityId>
#
# Env vars:
#   BASE_URL       API base URL (default: http://localhost:8080)
#   ADMIN_EMAIL    login email  (default: admin@evyoog.com)
#   ADMIN_PASSWORD login password (default: Admin@eVyoog1)

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@evyoog.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin@eVyoog1}"
LEGAL_ENTITY_ID="${1:-}"

if [[ -z "$LEGAL_ENTITY_ID" ]]; then
    echo "Usage: $0 <legalEntityId>" >&2
    exit 1
fi

for bin in curl jq; do
    command -v "$bin" >/dev/null 2>&1 || { echo "Missing required binary: $bin" >&2; exit 1; }
done

api() {
    local method="$1" path="$2" body="${3:-}"
    local response
    if [[ -n "$body" ]]; then
        response=$(curl -sS -w '\n%{http_code}' -X "$method" "${BASE_URL}${path}" \
            -H "Authorization: Bearer ${ACCESS_TOKEN}" \
            -H "X-User-Id: demo-seed-script" \
            -H "Content-Type: application/json" \
            -d "$body")
    else
        response=$(curl -sS -w '\n%{http_code}' -X "$method" "${BASE_URL}${path}" \
            -H "Authorization: Bearer ${ACCESS_TOKEN}" \
            -H "X-User-Id: demo-seed-script")
    fi

    local http_code payload
    http_code=$(tail -n1 <<<"$response")
    payload=$(sed '$d' <<<"$response")

    if [[ "$http_code" -ge 400 ]]; then
        echo "  FAILED: $method $path -> HTTP $http_code" >&2
        echo "  $payload" >&2
        exit 1
    fi

    echo "$payload"
}

echo "==> Logging in as ${ADMIN_EMAIL}"
LOGIN_RESPONSE=$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg email "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD" \
        '{email: $email, password: $password}')")

ACCESS_TOKEN=$(jq -r '.data.accessToken // empty' <<<"$LOGIN_RESPONSE")
if [[ -z "$ACCESS_TOKEN" ]]; then
    echo "Login failed:" >&2
    echo "$LOGIN_RESPONSE" >&2
    exit 1
fi
echo "  Logged in."

echo "==> [1/10] Checking for existing ledger on Legal Entity ${LEGAL_ENTITY_ID}"
EXISTING_LEDGER_ID=$(api GET "/api/v1/gl/legal-entity-ledgers?legalEntityId=${LEGAL_ENTITY_ID}" \
    | jq -r '.data[] | select(.ledgerCode == "PRIM-01") | .ledgerId' | head -n1)

if [[ -n "$EXISTING_LEDGER_ID" ]]; then
    LEDGER_ID="$EXISTING_LEDGER_ID"
    echo "  Ledger already linked: ${LEDGER_ID}"
else
    LEDGER_RESPONSE=$(api POST /api/v1/gl/ledgers "$(jq -n \
        '{
            code: "PRIM-01",
            name: "Primary Ledger",
            description: "Orbinox Valves India Pvt Ltd primary ledger",
            financeMode: "THICK",
            ledgerCategory: "PRIMARY",
            functionalCurrency: "INR",
            accountingStandard: "IND_AS"
        }')")
    LEDGER_ID=$(jq -r '.data.id' <<<"$LEDGER_RESPONSE")
    echo "  Ledger created: ${LEDGER_ID}"

    echo "==> [1/10] Linking ledger to Legal Entity"
    api POST /api/v1/gl/legal-entity-ledgers "$(jq -n \
        --arg legalEntityId "$LEGAL_ENTITY_ID" \
        --arg ledgerId "$LEDGER_ID" \
        '{legalEntityId: $legalEntityId, ledgerId: $ledgerId, ledgerCategory: "PRIMARY"}')" >/dev/null
    echo "  Linked."
fi

echo "==> [2/10] Checking for existing NATURAL_ACCOUNT Finance Dimension"
EXISTING_DIMENSION=$(api GET "/api/v1/gl/finance-dimensions?ledgerId=${LEDGER_ID}&dimensionType=NATURAL_ACCOUNT" \
    | jq -r '.data | length')

if [[ "$EXISTING_DIMENSION" -gt 0 ]]; then
    echo "  NATURAL_ACCOUNT Finance Dimension already exists — skipping."
else
    api POST /api/v1/gl/finance-dimensions "$(jq -n \
        --arg ledgerId "$LEDGER_ID" \
        '{
            ledgerId: $ledgerId,
            code: "NAT-ACCT",
            name: "Natural Account",
            description: "Chart of Accounts dimension",
            dimensionType: "NATURAL_ACCOUNT",
            isRequired: true,
            displayOrder: 1
        }')" >/dev/null
    echo "  Finance dimension created."
fi

echo "==> [3/10] Checking for existing accounting calendar"
EXISTING_CALENDAR_ID=$(api GET "/api/v1/gl/accounting-calendars?ledgerId=${LEDGER_ID}" | jq -r '.data.id // empty')

if [[ -n "$EXISTING_CALENDAR_ID" ]]; then
    CALENDAR_ID="$EXISTING_CALENDAR_ID"
    echo "  Calendar already exists: ${CALENDAR_ID}"
else
    CALENDAR_RESPONSE=$(api POST /api/v1/gl/accounting-calendars "$(jq -n \
        --arg ledgerId "$LEDGER_ID" \
        '{
            ledgerId: $ledgerId,
            name: "FY Calendar",
            description: "Standard Indian fiscal calendar",
            fiscalYearStartMonth: 4,
            fiscalYearStartDay: 1,
            periodType: "MONTHLY",
            initialFiscalYear: 2025
        }')")
    CALENDAR_ID=$(jq -r '.data.id' <<<"$CALENDAR_RESPONSE")
    GENERATED_COUNT=$(jq -r '.data.generatedPeriodCount' <<<"$CALENDAR_RESPONSE")
    echo "  Calendar created: ${CALENDAR_ID} (${GENERATED_COUNT} periods generated for FY 2025-26)"
fi

echo "==> [4/10] Checking for existing APR-2025 period status"
APR_2025_ID=$(api GET "/api/v1/gl/accounting-periods/find?calendarId=${CALENDAR_ID}&date=2025-04-15" | jq -r '.data.id')
echo "  APR-2025 period id: ${APR_2025_ID}"

EXISTING_PERIOD_STATUS_ROW=$(api GET "/api/v1/gl/period-status?legalEntityId=${LEGAL_ENTITY_ID}" \
    | jq -r '.data[] | select(.periodName == "APR-2025") | [.id, .status] | @tsv' | head -n1)

if [[ -n "$EXISTING_PERIOD_STATUS_ROW" ]]; then
    PERIOD_STATUS_ID=$(cut -f1 <<<"$EXISTING_PERIOD_STATUS_ROW")
    PERIOD_STATUS_STATE=$(cut -f2 <<<"$EXISTING_PERIOD_STATUS_ROW")
    echo "  APR-2025 period status already exists: ${PERIOD_STATUS_ID} (${PERIOD_STATUS_STATE})"
else
    PERIOD_STATUS_RESPONSE=$(api POST /api/v1/gl/period-status "$(jq -n \
        --arg legalEntityId "$LEGAL_ENTITY_ID" \
        --arg accountingPeriodId "$APR_2025_ID" \
        '{legalEntityId: $legalEntityId, accountingPeriodId: $accountingPeriodId}')")
    PERIOD_STATUS_ID=$(jq -r '.data.id' <<<"$PERIOD_STATUS_RESPONSE")
    PERIOD_STATUS_STATE=$(jq -r '.data.status' <<<"$PERIOD_STATUS_RESPONSE")
fi

if [[ "$PERIOD_STATUS_STATE" == "CLOSED" ]]; then
    echo "  APR-2025 already CLOSED — skipping open step."
elif [[ "$PERIOD_STATUS_STATE" == "OPEN" ]]; then
    echo "  APR-2025 already OPEN — skipping open step."
else
    api POST "/api/v1/gl/period-status/${PERIOD_STATUS_ID}/open" >/dev/null
    echo "  APR-2025 period opened."
    PERIOD_STATUS_STATE="OPEN"
fi

echo "==> [5/10] Fetching MANUAL journal source and ACCRUAL journal category ids"
MANUAL_SOURCE_ID=$(api GET /api/v1/gl/journal-sources | jq -r '.data[] | select(.code=="MANUAL") | .id')
ACCRUAL_CATEGORY_ID=$(api GET /api/v1/gl/journal-categories | jq -r '.data[] | select(.code=="ACCRUAL") | .id')
echo "  journalSourceId (MANUAL):   ${MANUAL_SOURCE_ID}"
echo "  journalCategoryId (ACCRUAL): ${ACCRUAL_CATEGORY_ID}"

echo "==> [6/10] Creating Chart of Accounts (25 accounts)"

# code|name|qualifier
ACCOUNTS=(
    "1100|Cash and Cash Equivalents|ASSET"
    "1200|Bank - HDFC Current Account|ASSET"
    "1300|Accounts Receivable|ASSET"
    "1400|Raw Material Inventory|ASSET"
    "1500|Work in Progress|ASSET"
    "1600|Finished Goods Inventory|ASSET"
    "1700|Plant and Machinery|ASSET"
    "1800|Accumulated Depreciation|ASSET"
    "2100|Accounts Payable|LIABILITY"
    "2200|GST Payable|LIABILITY"
    "2300|TDS Payable|LIABILITY"
    "2400|Employee Salaries Payable|LIABILITY"
    "2500|Short Term Bank Loan|LIABILITY"
    "3100|Share Capital|EQUITY"
    "3200|Retained Earnings|EQUITY"
    "4100|Valve Sales - Domestic|REVENUE"
    "4200|Valve Sales - Export|REVENUE"
    "4300|Service and AMC Revenue|REVENUE"
    "5100|Raw Material Consumed|EXPENSE"
    "5200|Direct Labour Cost|EXPENSE"
    "5300|Factory Overhead|EXPENSE"
    "5400|Salaries and Staff Cost|EXPENSE"
    "5500|Depreciation|EXPENSE"
    "5600|Marketing and Sales Expense|EXPENSE"
    "5700|Administrative Expenses|EXPENSE"
)

declare -A ACCOUNT_ID

for entry in "${ACCOUNTS[@]}"; do
    IFS='|' read -r code name qualifier <<<"$entry"

    existing_id=$(api GET "/api/v1/gl/chart-of-accounts/search?ledgerId=${LEDGER_ID}&query=${code}" \
        | jq -r --arg code "$code" '.data[] | select(.code == $code) | .id' | head -n1)

    if [[ -n "$existing_id" ]]; then
        ACCOUNT_ID["$code"]="$existing_id"
        echo "  ${code} ${name} already exists -> ${existing_id}"
        continue
    fi

    ACCOUNT_RESPONSE=$(api POST /api/v1/gl/chart-of-accounts "$(jq -n \
        --arg ledgerId "$LEDGER_ID" \
        --arg code "$code" \
        --arg name "$name" \
        --arg qualifier "$qualifier" \
        '{
            ledgerId: $ledgerId,
            code: $code,
            name: $name,
            qualifier: $qualifier,
            isSummary: false,
            isPostable: true
        }')")
    acct_id=$(jq -r '.data.id' <<<"$ACCOUNT_RESPONSE")
    ACCOUNT_ID["$code"]="$acct_id"
    echo "  ${code} ${name} (${qualifier}) -> ${acct_id}"
done

echo "==> [7/10] Creating Cost Centre (required) and Product (optional) Finance Dimensions"

EXISTING_CC_DIM=$(api GET "/api/v1/gl/finance-dimensions?ledgerId=${LEDGER_ID}&dimensionType=COST_CENTRE" | jq -r '.data[0].id // empty')
if [[ -n "$EXISTING_CC_DIM" ]]; then
    COST_CTR_DIM_ID="$EXISTING_CC_DIM"
    echo "  COST_CENTRE Finance Dimension already exists -> ${COST_CTR_DIM_ID}"
else
    COST_CTR_DIM_ID=$(api POST /api/v1/gl/finance-dimensions "$(jq -n \
        --arg ledgerId "$LEDGER_ID" \
        '{
            ledgerId: $ledgerId,
            code: "COST-CTR",
            name: "Cost Centre",
            description: "Cost centre dimension",
            dimensionType: "COST_CENTRE",
            isRequired: true,
            displayOrder: 2
        }')" | jq -r '.data.id')
    echo "  COST_CENTRE Finance Dimension created -> ${COST_CTR_DIM_ID}"
fi

EXISTING_PRODUCT_DIM=$(api GET "/api/v1/gl/finance-dimensions?ledgerId=${LEDGER_ID}&dimensionType=PRODUCT" | jq -r '.data[0].id // empty')
if [[ -n "$EXISTING_PRODUCT_DIM" ]]; then
    PRODUCT_DIM_ID="$EXISTING_PRODUCT_DIM"
    echo "  PRODUCT Finance Dimension already exists -> ${PRODUCT_DIM_ID}"
else
    PRODUCT_DIM_ID=$(api POST /api/v1/gl/finance-dimensions "$(jq -n \
        --arg ledgerId "$LEDGER_ID" \
        '{
            ledgerId: $ledgerId,
            code: "PRODUCT",
            name: "Product",
            description: "Product dimension",
            dimensionType: "PRODUCT",
            isRequired: false,
            displayOrder: 3
        }')" | jq -r '.data.id')
    echo "  PRODUCT Finance Dimension created -> ${PRODUCT_DIM_ID}"
fi

# code|name|description
COST_CENTRES=(
    "CC-MFG|Manufacturing|Manufacturing operations"
    "CC-SAL|Sales|Sales and marketing"
    "CC-ADM|Administration|General administration"
    "CC-RND|R&D|Research and development"
)

for entry in "${COST_CENTRES[@]}"; do
    IFS='|' read -r code name description <<<"$entry"
    existing=$(api GET "/api/v1/gl/dimension-values?financeDimensionId=${COST_CTR_DIM_ID}" \
        | jq -r --arg code "$code" '.data[] | select(.code == $code) | .id' | head -n1)
    if [[ -n "$existing" ]]; then
        echo "  Cost Centre ${code} already exists -> ${existing}"
        continue
    fi
    value_id=$(api POST /api/v1/gl/dimension-values "$(jq -n \
        --arg financeDimensionId "$COST_CTR_DIM_ID" --arg code "$code" --arg name "$name" --arg description "$description" \
        '{financeDimensionId: $financeDimensionId, code: $code, name: $name, description: $description,
          isPostable: true, displayOrder: 1}')" | jq -r '.data.id')
    echo "  Cost Centre ${code} ${name} -> ${value_id}"
done

# code|name|description
PRODUCTS=(
    "GATE-VLV|Gate Valves|Industrial gate valves"
    "BALL-VLV|Ball Valves|Industrial ball valves"
    "BFLY-VLV|Butterfly Valves|Industrial butterfly valves"
    "SPARES|Spare Parts|Spare parts and accessories"
    "SERVICES|Services|Service and AMC revenue"
)

for entry in "${PRODUCTS[@]}"; do
    IFS='|' read -r code name description <<<"$entry"
    existing=$(api GET "/api/v1/gl/dimension-values?financeDimensionId=${PRODUCT_DIM_ID}" \
        | jq -r --arg code "$code" '.data[] | select(.code == $code) | .id' | head -n1)
    if [[ -n "$existing" ]]; then
        echo "  Product ${code} already exists -> ${existing}"
        continue
    fi
    value_id=$(api POST /api/v1/gl/dimension-values "$(jq -n \
        --arg financeDimensionId "$PRODUCT_DIM_ID" --arg code "$code" --arg name "$name" --arg description "$description" \
        '{financeDimensionId: $financeDimensionId, code: $code, name: $name, description: $description,
          isPostable: true, displayOrder: 1}')" | jq -r '.data.id')
    echo "  Product ${code} ${name} -> ${value_id}"
done

echo "==> [8/10] Posting journal entries"

JOURNALS_RESPONSE=$(api GET "/api/v1/gl/journals?legalEntityId=${LEGAL_ENTITY_ID}&status=POSTED&size=50")
POSTED_COUNT=$(jq -r '(.data.content // .data // []) | length' <<<"$JOURNALS_RESPONSE" 2>/dev/null || echo "0")

# Cost Centre is a required Finance Dimension on this Ledger — every posted
# line must carry one. Product is optional — only tagged on revenue lines.
declare -A COST_CENTRE_FOR=(
    [1100]="CC-ADM" [1200]="CC-ADM" [1300]="CC-ADM" [1400]="CC-MFG" [1500]="CC-MFG"
    [1600]="CC-MFG" [1700]="CC-ADM" [1800]="CC-ADM"
    [2100]="CC-ADM" [2200]="CC-ADM" [2300]="CC-ADM" [2400]="CC-ADM" [2500]="CC-ADM"
    [3100]="CC-ADM" [3200]="CC-ADM"
    [4100]="CC-SAL" [4200]="CC-SAL" [4300]="CC-SAL"
    [5100]="CC-MFG" [5200]="CC-MFG" [5300]="CC-MFG" [5400]="CC-ADM" [5500]="CC-MFG"
    [5600]="CC-ADM" [5700]="CC-ADM"
)

declare -A PRODUCT_FOR=(
    [4100]="GATE-VLV" [4200]="GATE-VLV" [4300]="SERVICES"
)

# description|glDate|drCode|drAmount|crCode|crAmount
JOURNALS=(
    "Opening Balances|2025-04-01|1200|20000000|3100|20000000"
    "Domestic Valve Sales|2025-04-05|1300|7500000|4100|7500000"
    "Export Valve Sales - Bank Receipt|2025-04-08|1200|2000000|4200|2000000"
    "Service Revenue|2025-04-10|1300|500000|4300|500000"
    "Raw Material Purchase|2025-04-12|1400|2500000|2100|2500000"
    "Raw Material Consumed|2025-04-15|5100|2200000|1400|2200000"
    "Direct Labour Cost|2025-04-20|5200|1000000|2400|1000000"
    "Factory Overhead|2025-04-22|5300|800000|2100|800000"
    "Staff Salaries|2025-04-25|5400|300000|2400|300000"
    "Depreciation|2025-04-30|5500|200000|1800|200000"
)

JOURNAL_NUMBERS=()

if [[ "$POSTED_COUNT" -ge 10 ]]; then
    echo "  ${POSTED_COUNT} journals already posted for APR-2025 — skipping journal creation."
else
    for entry in "${JOURNALS[@]}"; do
        IFS='|' read -r description glDate drCode drAmount crCode crAmount <<<"$entry"

        dr_id="${ACCOUNT_ID[$drCode]}"
        cr_id="${ACCOUNT_ID[$crCode]}"
        dr_cc="${COST_CENTRE_FOR[$drCode]}"
        cr_cc="${COST_CENTRE_FOR[$crCode]}"
        dr_product="${PRODUCT_FOR[$drCode]:-}"
        cr_product="${PRODUCT_FOR[$crCode]:-}"

        LINES_JSON=$(jq -n \
            --arg drId "$dr_id" --arg drCode "$drCode" --argjson drAmount "$drAmount" --arg drCc "$dr_cc" --arg drProduct "$dr_product" \
            --arg crId "$cr_id" --arg crCode "$crCode" --argjson crAmount "$crAmount" --arg crCc "$cr_cc" --arg crProduct "$cr_product" \
            'def combo($natCode; $cc; $product):
                {NATURAL_ACCOUNT: $natCode, COST_CENTRE: $cc}
                + (if ($product // "") != "" then {PRODUCT: $product} else {} end);
             [
                {naturalAccountValueId: $drId, accountCombination: combo($drCode; $drCc; $drProduct), debitAmount: $drAmount},
                {naturalAccountValueId: $crId, accountCombination: combo($crCode; $crCc; $crProduct), creditAmount: $crAmount}
            ]')

        JOURNAL_BODY=$(jq -n \
            --arg legalEntityId "$LEGAL_ENTITY_ID" \
            --arg journalSourceId "$MANUAL_SOURCE_ID" \
            --arg journalCategoryId "$ACCRUAL_CATEGORY_ID" \
            --arg glDate "$glDate" \
            --arg description "$description" \
            --argjson lines "$LINES_JSON" \
            '{
                legalEntityId: $legalEntityId,
                journalSourceId: $journalSourceId,
                journalCategoryId: $journalCategoryId,
                glDate: $glDate,
                description: $description,
                lines: $lines
            }')

        JOURNAL_RESPONSE=$(api POST /api/v1/gl/journals "$JOURNAL_BODY")
        journal_number=$(jq -r '.data.journalNumber' <<<"$JOURNAL_RESPONSE")
        journal_status=$(jq -r '.data.status' <<<"$JOURNAL_RESPONSE")
        JOURNAL_NUMBERS+=("$journal_number")
        echo "  Posted: ${description} -> ${journal_number} (${journal_status})"
    done

    # NOTE: Year-end closing entry (transfer net income to Retained Earnings)
    # is intentionally excluded from demo seed data. Year-end close is a
    # deferred capability. Revenue and Expense accounts retain their balances
    # so P&L reports show meaningful data during the demo period.
fi

echo "==> [9/10] Closing APR-2025 period"
if [[ "$PERIOD_STATUS_STATE" == "CLOSED" ]]; then
    echo "  APR-2025 already CLOSED — skipping close step."
else
    api POST "/api/v1/gl/period-status/${PERIOD_STATUS_ID}/close" "$(jq -n \
        --arg closedBy "$ADMIN_EMAIL" \
        '{closedBy: $closedBy}')" >/dev/null
    PERIOD_STATUS_STATE="CLOSED"
    echo "  APR-2025 period closed."
fi

echo ""
echo "==> [10/10] Done. Summary:"
echo "  legalEntityId: ${LEGAL_ENTITY_ID}"
echo "  ledgerId:      ${LEDGER_ID}"
echo "  calendarId:    ${CALENDAR_ID}"
echo "  APR-2025 id:   ${APR_2025_ID}"
echo "  APR-2025 status: CLOSED"
echo ""
echo "  Chart of Accounts (${#ACCOUNTS[@]}):"
for entry in "${ACCOUNTS[@]}"; do
    IFS='|' read -r code name qualifier <<<"$entry"
    echo "    ${code}  ${name}  (${qualifier})  -> ${ACCOUNT_ID[$code]}"
done
echo ""
if [[ "${#JOURNAL_NUMBERS[@]}" -gt 0 ]]; then
    echo "  Journals posted (${#JOURNAL_NUMBERS[@]}):"
    for jn in "${JOURNAL_NUMBERS[@]}"; do
        echo "    ${jn}"
    done
else
    echo "  Journals: none posted this run (already present)."
fi
echo ""
echo "  Expected April 2025 P&L for Orbinox Valves India Pvt Ltd:"
echo "    Revenue:  INR 1,00,00,000  (Domestic 75,00,000 + Export 20,00,000 + Service 5,00,000)"
echo "    Expenses: INR 45,00,000    (Raw Material 22,00,000 + Labour 10,00,000 + Overhead 8,00,000 + Salaries 3,00,000 + Depreciation 2,00,000)"
echo "    Profit:   INR 55,00,000"
