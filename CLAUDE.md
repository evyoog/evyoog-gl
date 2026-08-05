# CLAUDE.md — eVyoog ERP · GL Module

> This file is read automatically by Claude Code at the start of every session.
> It is the authoritative reference for all implementation decisions.
> Do not contradict it. Do not deviate from it without explicit instruction from the Domain SME.

---

## Who you are building for

**Product**: eVyoog ERP — a capability-based modular ERP for Indian discrete manufacturing SMEs.
**Domain SME**: Prashanth — provides business rules and validates correctness.
**Your role**: Implementer. You write the code. The Domain SME decides if it is correct.

---

## Architecture — PLATFORM_FOUNDATION_v2.0

### Pattern: Capability-based Modular (NOT microservices)

- All GL capabilities share **one Spring Boot application** and **one PostgreSQL database**
- Schema boundary is at the **module level** — `gl` schema owns all GL tables, `aie` schema owns AIE tables
- **Write isolation**: the GL service writes ONLY to `gl.*` and `aie.*` — no other schema, ever
- **Cross-schema reads**: permitted for reporting (SQL JOINs) — never for writes
- **Transactional cross-module flows**: events only — never direct cross-schema writes
- **Capability**: the activation and billing unit — independently activatable per customer via `gl.capability_registry`

### The four rules you must never break

```
Rule 1 — Write isolation:  GL service writes ONLY to gl.* and aie.*
Rule 2 — Audit every write: every INSERT/UPDATE on any entity → audit_log in the SAME transaction
Rule 3 — Soft delete only: never DELETE from gl.*. Set is_active = FALSE
Rule 4 — UUID keys: all primary keys are UUID via uuid_generate_v4()
```

---

## Tech stack

| Component | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.x |
| ORM | Spring Data JPA + Hibernate |
| Database | PostgreSQL 16 |
| Migrations | Flyway (classpath:db/migration) |
| DTO mapping | MapStruct 1.5.5 |
| Boilerplate | Lombok |
| API docs | Springdoc OpenAPI 3 (Swagger UI at /swagger-ui.html) |
| JSONB mapping | Hibernate 7 native (Spring Boot 4) — no external library needed |
| Unit tests | JUnit 5 + Mockito |
| Integration tests | Testcontainers (PostgreSQL container) |
| Build | Maven |

---

## Database

```
Database name : evyoog_gl
Schemas       : gl (25 tables)  ·  aie (6 tables)
Total         : 31 tables · 92 indexes · 3 views
Extensions    : uuid-ossp · pgcrypto
Migrations    : Flyway V1__baseline.sql = full evyoog_gl_schema_v2.sql
                Subsequent: V2__, V3__ etc — one per capability
```

### Connection (from environment variables — never hardcode)

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/evyoog_gl}
    username: ${SPRING_DATASOURCE_USERNAME:evyoog_app}
    password: ${SPRING_DATASOURCE_PASSWORD:evyoog_dev_pass}
  jpa:
    hibernate:
      ddl-auto: validate   # Flyway manages schema — JPA only validates
    properties:
      hibernate:
        default_schema: gl
```

### JSONB columns

`gl.journal_line.account_combination` and `gl.account_balance.account_combination` are `jsonb`. Map with:

```java
// Hibernate 7 JSONB mapping for Map<String,String> and Map<String,Object>:
// Use @JdbcTypeCode(SqlTypes.JSON) on the field
// Import: org.hibernate.annotations.JdbcTypeCode and org.hibernate.type.SqlTypes
@Column(name = "account_combination", columnDefinition = "jsonb")
private Map<String, String> accountCombination;
```

---

## Project package structure

```
com.evyoog.gl
├── EvyoogGlApplication.java
├── config/                        ← DataSource, OpenAPI, Flyway config
├── common/
│   ├── audit/                     ← AuditLog entity + AuditService (shared by ALL capabilities)
│   ├── exception/                 ← EvyoogException, ResourceNotFoundException, GlobalExceptionHandler
│   └── response/                  ← ApiResponse<T> wrapper
├── enterprise/                    ← GL-01 Enterprise Setup
│   ├── api/                       ← Controllers
│   ├── service/                   ← Business logic + validation
│   ├── repository/                ← Spring Data JPA repositories
│   ├── domain/                    ← JPA entities
│   ├── dto/                       ← Request + Response DTOs
│   └── mapper/                    ← MapStruct mappers
├── ledger/                        ← GL-03 Ledger Management
├── dimension/                     ← GL-04 Finance Dimensions
├── coa/                           ← GL-05 Chart of Accounts
├── calendar/                      ← GL-08 Accounting Calendar
├── period/                        ← GL-09 + GL-10 Period Management + Status
├── journal/                       ← GL-11 through GL-15 Journal + Posting Engine
├── aie/                           ← GL-16 through GL-20 AIE Pipeline
├── balance/                       ← GL-21 Account Balance
├── reporting/                     ← GL-22 through GL-26 Reports
└── localisation/                  ← GL-27 + GL-28 India GST / TDS
```

---

## Non-negotiable implementation rules

### 1. Audit trail — every write, same transaction

```java
@Service
@RequiredArgsConstructor
public class BusinessGroupService {

    private final BusinessGroupRepository repository;
    private final AuditService auditService;

    @Transactional
    public BusinessGroupResponse create(CreateBusinessGroupRequest request) {
        BusinessGroup entity = mapper.toEntity(request);
        BusinessGroup saved = repository.save(entity);

        // Audit MUST be in same transaction
        auditService.log(AuditAction.CREATE, "business_group", saved.getId(),
                        null, saved, requestContext.getUserId());

        return mapper.toResponse(saved);
    }
}
```

### 2. Never expose JPA entities in API responses

```java
// WRONG — never do this
@GetMapping("/{id}")
public BusinessGroup getById(@PathVariable UUID id) { ... }

// RIGHT — always use DTOs
@GetMapping("/{id}")
public ApiResponse<BusinessGroupResponse> getById(@PathVariable UUID id) { ... }
```

### 3. Structured error responses — always

```java
// Every error response must follow this shape:
{
  "status": 409,
  "code": "THIN_ES_LE_LIMIT",
  "message": "Thin ES Business Groups support exactly one Legal Entity.",
  "field": "businessGroupId",
  "timestamp": "2026-06-01T10:30:00Z"
}
```

### 4. Soft delete — never hard delete

```java
// WRONG
repository.deleteById(id);

// RIGHT
entity.setIsActive(false);
repository.save(entity);
auditService.log(AuditAction.DELETE, ...);
```

### 5. finance_mode_snapshot — set once, never update

```java
// On journal_header creation ONLY:
journalHeader.setFinanceModeSnapshot(ledger.getFinanceMode());
// Never update this field after creation, ever.
```

---

## Business rules by area

### Thin ES constraints

```java
// A Business Group with es_mode = THIN_ES may have exactly ONE Legal Entity
if (businessGroup.getEsMode() == EsMode.THIN_ES) {
    long count = legalEntityRepository.countByBusinessGroupId(businessGroup.getId());
    if (count >= 1) {
        throw new EvyoogException("THIN_ES_LE_LIMIT",
            "Thin ES Business Groups support exactly one Legal Entity.");
    }
}

// A Ledger with finance_mode = THIN may have exactly TWO Finance Dimensions
if (ledger.getFinanceMode() == FinanceMode.THIN) {
    long count = financeDimensionRepository.countByLedgerId(ledger.getId());
    if (count >= 2) {
        throw new EvyoogException("THIN_DIMENSION_LIMIT",
            "Thin mode Ledgers support exactly two Finance Dimensions: LEGAL_ENTITY and NATURAL_ACCOUNT.");
    }
}
```

### GSTIN validation

```java
private static final Pattern GSTIN_PATTERN =
    Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");

public void validateGstin(String gstin) {
    if (gstin != null && !GSTIN_PATTERN.matcher(gstin).matches()) {
        throw new ValidationException("INVALID_GSTIN",
            "GSTIN must be a valid 15-character Indian GST number.");
    }
}
```

### Posting engine — mode branching (read finance_mode ONCE)

```java
@Service
public class PostingEngine {

    public PostingResult post(JournalHeader header, List<JournalLine> lines) {
        // Read finance_mode ONCE — never check it again inside this method
        FinanceMode mode = header.getLedger().getFinanceMode();

        return switch (mode) {
            case THICK  -> postThick(header, lines);
            case THIN   -> postThin(header, lines);
            case EVENT_ONLY -> emitEvent(header, lines);
        };
    }

    private PostingResult postThick(JournalHeader header, List<JournalLine> lines) {
        // All 8 validation rules
        validateBalance(lines);
        validatePeriodOpen(header);
        validateAccounts(lines, header.getLedger());
        validatePostableAccounts(lines);
        validateDimensionValues(lines, header.getLedger());
        validateLegalEntityAuthorisation(header);
        validateCurrency(header);
        validateApproval(header);
        // Update account_balance atomically
        updateAccountBalance(header, lines);
        return PostingResult.posted(header.getId());
    }
}
```

### Period gate — check on every posting

```java
PeriodStatus status = periodStatusRepository
    .findByLegalEntityIdAndAccountingPeriodId(
        header.getLegalEntityId(), header.getAccountingPeriodId())
    .orElseThrow(() -> new EvyoogException("PERIOD_NOT_FOUND", "Period not open for this Legal Entity."));

if (status.getStatus() != PeriodStatusEnum.OPEN) {
    throw new EvyoogException("PERIOD_NOT_OPEN",
        "Accounting period is " + status.getStatus() + ". Cannot post journals to a non-open period.");
}
```

---

## Three financial modes — what each allows

| Behaviour | THICK | THIN | EVENT_ONLY |
|---|---|---|---|
| Journal entries | Yes — full double-entry | Yes — lightweight | No |
| Finance Dimensions | 2–15 | Exactly 2 | None required |
| Period status gate | Yes | Yes | No (Phase 1) |
| account_balance update | Yes | Yes | No |
| Period-close workflow | Full 7-step | Simplified | None |
| Trial Balance | Full | Simplified | N/A |
| Balance Sheet | Yes | No | N/A |
| Reversal journals | Yes | No | N/A |
| Recurring journals | Yes | No | N/A |
| SLA events to Kafka | No | No | Yes |

---

## India localisation

### GST model — 1 BU = 1 State = 1 GSTIN

```
Business Unit A (Tamil Nadu) → GSTIN: 33AABCE1234F1Z5
Business Unit B (Maharashtra) → GSTIN: 27AABCE1234F1Z5
Both BUs belong to the same Legal Entity.
```

- Intra-state transaction: CGST + SGST (each at half the rate)
- Inter-state transaction: IGST (full rate)
- GST flags on `gl.journal_line`: `gst_applicable`, `gst_type` (CGST/SGST/IGST/UTGST)

### TDS

- Fields on `gl.journal_line`: `tds_applicable`, `tds_section` (194C, 194J, 194H, 192, etc.)
- `gl.legal_entity.tan` — Tax Deduction Account Number

### Fiscal year

- April 1 to March 31 — default for all new calendars
- Period naming: APR-2025, MAY-2025, ..., MAR-2026
- Fiscal year name: 2025-26

---

## Testing requirements

### Unit test pattern

```java
@ExtendWith(MockitoExtension.class)
class LegalEntityServiceTest {

    @Mock
    private LegalEntityRepository repository;
    @Mock
    private BusinessGroupRepository bgRepository;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private LegalEntityService service;

    @Test
    void createLegalEntity_whenThinEsAlreadyHasOne_shouldThrow409() {
        // given
        BusinessGroup bg = BusinessGroup.builder().esMode(EsMode.THIN_ES).build();
        when(bgRepository.findById(any())).thenReturn(Optional.of(bg));
        when(repository.countByBusinessGroupId(any())).thenReturn(1L);

        // when / then
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "THIN_ES_LE_LIMIT");
    }
}
```

### Integration test pattern

```java
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class LegalEntityControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("evyoog_gl_test")
            .withUsername("evyoog_app")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
}
```

### Commands to run

```bash
mvn test             # Unit tests — no DB needed (~30 seconds)
mvn verify           # Unit + integration tests — Testcontainers spins up PostgreSQL
mvn spring-boot:run  # Start app on port 8080 — test API via Codespaces port-forwarding
```

---

## API response envelope

All API responses use a consistent envelope:

```java
public record ApiResponse<T>(
    boolean success,
    T data,
    String message,
    List<FieldError> errors
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, data, null, null);
    }
    public static ApiResponse<?> error(String message, List<FieldError> errors) {
        return new ApiResponse<>(false, null, message, errors);
    }
}
```

```
HTTP 200 OK          → successful GET
HTTP 201 Created     → successful POST
HTTP 400 Bad Request → validation failure (field-level errors)
HTTP 404 Not Found   → resource does not exist
HTTP 409 Conflict    → business rule violation (Thin ES limit, duplicate GSTIN, etc.)
HTTP 422 Unprocessable → posting validation failure (period not open, DR ≠ CR, etc.)
```

---

## Build sequence — current position

Always check which capability is being built in this session. Follow this sequence strictly:

```
✓ = done and merged
→ = next to build
  = not started

✓ GL-01 Enterprise Setup
✓ GL-02 Setup Wizard
✓ GL-03 Ledger Management
✓ GL-04 Finance Dimension Management
→ GL-05 Chart of Accounts Management
  GL-08 Accounting Calendar
  GL-09 Period Management
  GL-10 Period Status Control
  GL-15 Posting Engine
  GL-11 Manual Journal Entry
  GL-21 Account Balance Maintenance
  GL-22 Trial Balance            ← First major milestone 🎯
```

---

## Before ending any session

Run this checklist before considering a capability done:

```bash
# 1. All tests pass
mvn verify

# 2. App starts cleanly
mvn spring-boot:run

# 3. OpenAPI spec generated
curl http://localhost:8080/api-docs

# 4. Commit and push
git add .
git commit -m "feat(GL-XX): <capability name> — all tests passing"
git push origin main
```

---

## What NOT to do

- Do NOT use `spring.jpa.hibernate.ddl-auto: create` or `update` — Flyway manages the schema
- Do NOT hard-delete rows from `gl.*` — soft delete only (`is_active = FALSE`)
- Do NOT expose JPA entities in API responses — always use DTOs
- Do NOT scatter `finance_mode` checks across multiple services — the Posting Engine reads it once and branches
- Do NOT update `journal_header.finance_mode_snapshot` after creation — it is immutable
- Do NOT accept free-text dimension values — validate against `dimension_value` master before use
- Do NOT write to any schema other than `gl.*` and `aie.*`
- Do NOT create a second Legal Entity under a THIN_ES Business Group
- Do NOT create a third Finance Dimension under a THIN mode Ledger
- Do NOT post a journal to a period that is not OPEN for the journal's Legal Entity

---

*eVyoog ERP · CLAUDE.md · PLATFORM_FOUNDATION_v2.0 · GL_CORE_v1.0*
*Keep this file in the root of the repository. Claude Code reads it automatically.*

---

## Technical Deviations — Discovered During Build (GL-02 through GL-13)

These correct the original CLAUDE.md assumptions. Claude Code MUST read these
before building any future capability.

### GL-02 — ConsumptionContext field name
- Field is `segmentType` (NOT `contextType`) on ConsumptionContext entity
- ConsumptionContext has: `code`, `name`, `segmentType`, `organisationName`,
  `primaryContactName`, `primaryContactEmail`

### GL-02 — provisioningAnswers
- `provisioningAnswers` column was missing from the V1 baseline entity
- Added via V2 migration — now present on ConsumptionContext

### GL-04 — DimensionValue parentValue
- `DimensionValue.parentValue` is a `@ManyToOne` entity relation (NOT a raw UUID field)
- Access via `dv.getParentValue().getId()` — not `dv.getParentValueId()`

### GL-05 — DimensionType enum
- `DimensionType` enum acts as Oracle-style segment qualifier on `DimensionValue`
- Used as `segmentType` on `ConsumptionContext` (confirmed naming)

### GL-05 — Intercompany dimension values
- IC dimension values require `counterparty_legal_entity_id` FK (added via V5 migration)

### GL-05 — Cost Centre metadata
- `dimension_value` has: `cc_manager_name`, `cc_manager_email`, `cc_department`,
  `valid_from`, `valid_to`, `budget_controlled` (added via V5 migration)

### GL-11 — JSONB on Map fields (Hibernate 7)
- Hibernate 7 native JSONB requires `@JdbcTypeCode(SqlTypes.JSON)` on Map fields
- Do NOT use `hibernate-types-60` or `@Type` annotation — these do not work with Hibernate 7
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "account_combination", columnDefinition = "jsonb")
private Map<String, String> accountCombination;
```

### GL-11 — currency codes
- Use `VARCHAR(3)` NOT `CHAR(3)` for currency code columns

### GL-11 — AuditableEntity
- Subclasses MUST NOT redeclare `isActive` — it is already on `AuditableEntity`
- Redeclaring it causes Hibernate mapping conflicts

### GL-11 — Timestamp annotations
- Use `@CreationTimestamp` / `@UpdateTimestamp` with `Instant` type
- Do NOT use manual `OffsetDateTime.now()` defaults

### GL-11 — JournalLine debit/credit columns
- `JournalLine` uses SPLIT columns: `debitAmount` and `creditAmount` (BigDecimal)
- NOT a single `amount` + `debitCredit` string as the design doc assumed
- Reversal (GL-13) flips by swapping the two fields

### GL-12 — PostingEngine row creation (CRITICAL)
- `PostingEngine.post()` always creates a NEW JournalHeader row (new ID, new journal number)
- It does NOT update existing rows in place — this is tested, load-bearing behavior
- Do NOT attempt to refactor PostingEngine in any future capability
- ApprovalService.approve() follows this pattern:
    original row updated to APPROVED → PostingEngine creates new POSTED row
    approval-history and approve endpoint keyed to original journal ID

### GL-12 — Period-open gate location
- The period-open gate lives SOLELY inside `PostingEngine.postThick()`
- Do NOT duplicate this check in ApprovalService, ReversalService, or any other service

### GL-13 — Reversal column names (CRITICAL)
- Reversal columns are: `reversal_of_id` (NOT `original_journal_id`) and `is_reversal`
- Both exist from V9 baseline — V19 only added a partial index
- `findByOriginalJournalId` query method must use `reversal_of_id` column

### GL-13 — Lombok Boolean wrapper
- `is_reversal` maps to `Boolean` (wrapper, NOT boolean primitive)
- Lombok generates: `getIsReversal()` / `setIsReversal(Boolean)`
- NOT `setReversal()` — the "is" stripping only applies to primitive boolean

### GL-13 — JournalHeader entity relations
- `legalEntity`, `ledger`, `accountingPeriod` on JournalHeader are `@ManyToOne` entity relations
- NOT raw UUID fields — access via `journal.getLegalEntity().getId()` etc.

### GL-13 — REVERSAL seed data
- `REVERSAL` journal_source and journal_category already seeded from V9 baseline
- Do NOT re-insert in any future migration

### General — .claude/ folder
- `.claude/` is in `.gitignore` — do not commit Claude Code session files

### General — Spring Boot version
- Actual version: Spring Boot 4.0.7 (NOT 3.3.x as original CLAUDE.md stated)
- Java 21 (Microsoft build), Hibernate 7, MapStruct 1.6.3, Springdoc OpenAPI 3.0.2


### GL-14 — recurring_journal_template
- Table did NOT exist in V1 baseline — created in full in V20
- Template lines stored as typed POJO list (RecurringTemplateLine), NOT List<Map<String,Object>>
- Raw Map<String,Object> deserializes BigDecimal as Double via Hibernate 7 JSON — 
  always use a typed POJO for JSONB that contains numeric fields

### GL-14 — Lombok boolean vs Boolean convention
- primitive boolean fields (isActive on AuditableEntity): Lombok generates
  isActive() / setActive(false)  ← no "Is" in setter
- Boolean wrapper fields (isReversal on JournalHeader): Lombok generates
  getIsReversal() / setIsReversal(Boolean)  ← "Is" preserved in both
- Check FinanceDimensionService.deactivate() as the reference pattern for isActive
- Check JournalHeader as the reference pattern for Boolean wrapper fields

### GL-14 — journal_category RECURRING seed
- RECURRING journal_source: already seeded in V9
- RECURRING journal_category: NOT seeded until V20 — seeded there

### GL-16 — aie schema reality (CRITICAL for GL-29)
- Only aie.sla_event_log existed from V1 baseline (owned by PostingEngine.emitEvent()
  for EVENT_ONLY mode) — do NOT overload it
- interface_batch, interface_line, interface_error, deduplication_log:
  all created in V21 — did NOT exist before GL-16
- GL-16 post-stage acknowledgement uses aie.batch_ack_log (new in V21),
  not aie.sla_event_log

### GL-16 — pipeline transaction pattern
- All 4 stages run in one @Transactional method
- Validation/enrichment/posting failures are CAUGHT (not thrown) so FAILED
  batch persists with line-level errors — only DUPLICATE_EVENT_ID throws
  before any row is written
- This pattern makes GET /batches/{id}/errors and resubmit meaningful

### GL-16 — enrichment pattern
- accountCode resolved against Ledger's NATURAL_ACCOUNT FinanceDimension
  (same pattern PostingEngine uses internally)
- GST/TDS flags filled from DimensionValue account master when caller omits them

### AUTH-01 — auth schema is a third GL-service-owned schema (Rule 1 update)
- Rule 1 ("GL service writes ONLY to gl.* and aie.*") predates AUTH-01. The `auth`
  schema is now also owned and written to by this same Spring Boot service, following
  the identical capability-based-schema pattern as `gl` and `aie`. Do NOT write to any
  OTHER schema — the rule's spirit (write isolation to this service's own schemas) still
  holds, only the schema list grew from 2 to 3.
- `spring.flyway.schemas` and `spring.datasource.hikari` connect against `evyoog_gl` as
  before — `auth` tables live in the same physical database, just a different schema,
  same as `aie`.

### AUTH-01 — @PreAuthorize is evaluated inside DispatcherServlet, not the filter chain
- A denied `@PreAuthorize` throws `AccessDeniedException` from the method-security AOP
  interceptor DURING controller invocation (inside `DispatcherServlet.doDispatch()`).
  Spring MVC's own `@RestControllerAdvice` (`GlobalExceptionHandler`) gets first chance
  at that exception via `@ExceptionHandler(Exception.class)`, so it is caught there —
  it NEVER reaches `SecurityConfig`'s `accessDeniedHandler`, which only sees exceptions
  thrown by the filter chain itself (before dispatch, e.g. authentication failures from
  `.anyRequest().authenticated()`).
- `GlobalExceptionHandler` therefore has explicit `@ExceptionHandler(AccessDeniedException.class)`
  (→ 403 ACCESS_DENIED) and `@ExceptionHandler(AuthenticationException.class)` handlers.
  `SecurityConfig`'s `authenticationEntryPoint`/`accessDeniedHandler` remain for the
  filter-chain-level 401 path (missing/invalid bearer token, thrown before MVC dispatch
  ever starts) — that path is NOT touched by `GlobalExceptionHandler`.

### AUTH-01 — SYS_ADMIN bootstrap password via pgcrypto
- `crypt('Admin@eVyoog1', gen_salt('bf', 12))` (pgcrypto, already enabled by V1 baseline)
  produces a standard `$2a$` bcrypt hash that Spring Security's `BCryptPasswordEncoder`
  verifies directly — no Java-side hash generation or `@PostConstruct` seeding needed.
  Confirmed working via live login smoke test.

### AUTH-01 — seeded SYS_ADMIN has no Legal Entity / role assignment (manual bootstrap required)
- `auth.user_roles.legal_entity_id` is `NOT NULL` with an FK to `gl.legal_entity`, and no
  legal entities exist at V23 migration time — so the seed migration creates the
  `admin@evyoog.com` row but assigns it NO role. Logging in as the seeded admin before
  any Legal Entity + role assignment exists correctly returns 400 `NO_LE_ASSIGNED`, not
  a working session. This is by design, not a bug — confirmed via live smoke test.
- This is a genuine cold-start bootstrap problem, not just a missing convenience step:
  every endpoint is now `@PreAuthorize`-gated, including enterprise setup
  (`gl:enterprise:manage` on `POST /api/v1/gl/legal-entities`) and user/role management
  (`gl:users:edit` on `POST /api/v1/auth/users/{id}/roles`). There is no bootstrap token,
  so on a brand-new database NEITHER of those endpoints is callable yet — calling the API
  to fix this is circular.
- The only way to bring up a fresh environment: after the first `gl.legal_entity` row
  exists (either it predates AUTH-01, e.g. an already-provisioned dev/test database, or
  someone inserts one directly via SQL), manually `INSERT INTO auth.user_roles
  (user_id, role_id, legal_entity_id, assigned_by) SELECT u.id, r.id, '<legal_entity_id>',
  'SYSTEM' FROM auth.users u, auth.roles r WHERE u.email = 'admin@evyoog.com' AND
  r.code = 'SYS_ADMIN';` directly against the database. After that one manual row, admin
  can log in and use the API (including `POST /api/v1/auth/users/{id}/roles`) normally
  for every subsequent user/role assignment.

### AUTH-01 — permission catalog extends beyond the original spec's seed list
- The original AUTH-01 spec's permission seed only covered journal/reporting/COA/period/
  recurring/aie/gst/tds/audit/users/roles. Endpoints for enterprise setup (business
  group/legal entity/business unit/inventory org/sub-inventory/consumption context),
  ledger management, finance dimensions, account balance, setup wizard, and approval
  policy configuration needed new permission codes, added in the same V23 migration:
  `gl:enterprise:{view,manage}`, `gl:ledger:{view,manage}`, `gl:dimension:{view,manage}`,
  `gl:balance:{view,manage}`, `gl:wizard:{run,view}`, `gl:approval-policy:{view,manage}`.
  `GL_VIEWER`/`GL_AUDITOR` pick these up automatically (seeded by `action` matching, not
  an explicit code list) — only `GL_MANAGER`/`GL_ACCOUNTANT`'s explicit code lists needed
  extending. AccountingCalendar/AccountingPeriod/PeriodStatus all reuse the existing
  `gl:period:{view,manage}` codes rather than getting their own — they're one epic.

### AUTH-01 — IT suite: one MockMvc customizer, one merged IT class
- All ~25 pre-AUTH-01 IT files predate authentication and never set an Authorization
  header. Rather than touch every one of them, `src/test/java/com/evyoog/gl/testsupport/
  TestJwtMockMvcCustomizer` implements `MockMvcBuilderCustomizer` (auto-discovered by
  Spring Boot's component scan since it's in the `com.evyoog.gl` tree, even though it
  lives under `src/test/java`) and calls `builder.defaultRequest(...)` with a superuser
  JWT holding every permission in `auth.permissions`. Per-request headers set explicitly
  in a test override the default (`ConfigurableMockMvcBuilder#defaultRequest` javadoc:
  "properties specified at the time of performing a request override the default
  properties"), so AUTH-specific tests can still exercise 401/403 by supplying their own
  (invalid, or a real lower-privilege user's) Authorization header.
- Do NOT declare a shared `@Container static PostgreSQLContainer` field in an abstract
  base class extended by multiple IT test classes. Testcontainers' JUnit5 extension stops
  static `@Container` fields in that OWNING class's `afterAll` — since Java static fields
  declared in a superclass are one shared object, the FIRST subclass to finish its tests
  kills the container for every sibling subclass still to run, and every later IT class
  fails with Hikari "Connection is not available" (pool literally never connects again).
  This is why all AUTH-01 IT coverage lives in one self-contained class,
  `auth/api/AuthControllerIT`, each owning its full Testcontainers lifecycle exactly like
  every other IT file in the repo — not a shared abstract base.

### GL-20 — SlaEventLog reality is far simpler than any future spec will assume (CRITICAL)
- `aie.sla_event_log` (V9 baseline) has only: `id`, `ledger_id`, `legal_entity_id`,
  `accounting_period_id`, `event_payload` (JSONB `Map<String,Object>`), `status`
  (defaults `'EMITTED'`), `created_at`. There is NO `batch_id`, NO `journal_header_id`,
  and NO `event_type` column — do not assume any future spec's field list without
  re-inspecting the entity first. `PostingEngine.emitEvent()` (GL-15) is still the only
  writer, for EVENT_ONLY-mode `PostingRequest.eventPayload`.
- GL-20's REST surface was adapted to the real columns:
  `GET /api/v1/gl/events` (filters: `legalEntityId`, `ledgerId`, `accountingPeriodId`,
  `status`, `from`, `to` — at least one required, else 400 `EVENT_FILTER_REQUIRED`,
  same guard pattern as GL-29's `AUDIT_FILTER_REQUIRED`), `GET /api/v1/gl/events/{id}`
  (404 `EVENT_NOT_FOUND`), and `GET /api/v1/gl/events/legal-entity/{legalEntityId}`
  (paginated) — there is no journal- or batch-scoped endpoint because those FKs don't
  exist on the entity. Package `com.evyoog.gl.event` (mirrors GL-29's separate
  `com.evyoog.gl.audit` package rather than nesting under `com.evyoog.gl.aie`).
- No REST endpoint sets `PostingRequest.eventPayload` today — `CreateJournalRequest`
  (GL-11) has no `eventPayload` field, so EVENT_ONLY journals can currently only be
  posted by calling `PostingEngine.post()` directly (as `PostingEngineIT` and GL-20's
  own `SlaEventControllerIT` both do). This is a pre-existing gap, not introduced by
  GL-20 — out of scope to fix here.
- V25 added `idx_sla_event_status`, `idx_sla_event_period`, `idx_sla_event_created_at`
  alongside the V9 baseline's `idx_sla_event_ledger`/`idx_sla_event_le`.

### GL-19 — je_source_reference did NOT exist anywhere (CRITICAL — bigger gap than GL-14/GL-20)
- The build spec claimed `aie.je_source_reference` "already exists from V1 baseline" and
  told Claude Code only to add indexes if needed. This was verified false two ways:
  grepping every migration file (`V1` through `V25`) for `je_source_reference`/`source_ref`
  found nothing, and a live query against `evyoog-postgres`
  (`SELECT table_name FROM information_schema.tables WHERE table_schema='aie'`) confirmed
  the schema only had `interface_batch`, `interface_line`, `interface_error`,
  `deduplication_log`, `batch_ack_log`, `sla_event_log` — no `je_source_reference`. There
  is also no "V1 baseline" migration file at all; migrations are one-per-capability from
  `V1__gl01_enterprise_setup.sql` onward. GL-19 created the table from scratch in
  `V26__gl19_je_source_reference.sql`, matching the column list the spec assumed (that
  part was accurate) plus `idx_je_source_ref_journal_header` and
  `idx_je_source_ref_source_lookup` (composite on `source_system, source_document_id`,
  the lookup key `GET /source-references/source/{system}/{docId}` filters on).
- `je_source_reference` has no `is_active` column — the DELETE endpoint (only allowed
  while the linked journal is DRAFT, per spec) does a real hard `repository.delete(...)`,
  not a soft delete. This is consistent with Rule 3's actual scope ("never DELETE from
  `gl.*`") — `je_source_reference` lives in `aie.*`, and unlike GL-*'s core financial
  records this is a disposable linking row with no historical/audit value once removed
  (the audit_log entry itself is the permanent record of the deletion).
- **Audit logging pitfall — pass the response DTO, not the entity.** The first cut of
  `SourceReferenceService.create()`/`delete()` called
  `auditService.log(..., saved, ...)` with the raw `JeSourceReference` entity (which has
  `@ManyToOne JournalHeader journalHeader`). Every IT test hit 500 `INTERNAL_ERROR` — the
  Jackson serialization of the entity graph (traversing the lazy `journalHeader` proxy and
  its own associations) blew up inside `AuditService.log()`'s `objectMapper.writeValueAsString()`,
  caught only by `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)`.
  Every other capability's service (`FinanceDimensionService`, `UserService`,
  `ApprovalPolicyService`, `RoleService`, etc.) passes the mapped response DTO to
  `auditService.log(...)`, never the raw entity — this is the actual, load-bearing
  convention, not just a style preference. Follow it whenever the entity being audited
  has a JPA relation field.

### Codespaces deployment
- Port 8080 must be set to Public visibility for React frontend to reach it
- CORS allows: http://localhost:5173 and the Codespaces frontend public URL
- SYS_ADMIN bootstrap: direct SQL INSERT into auth.user_roles — cannot use API
- BCrypt password reset: use BCryptPasswordEncoder(12) via Claude Code + SQL UPDATE
- Period endpoint: /api/v1/gl/period-status (not /api/v1/gl/periods)

### Demo data — Orbinox Valves India Pvt Ltd
- Company: Orbinox Valves India Pvt Ltd (industrial valve manufacturer)
- Legal Entity ID: d53c88e1-1bd0-4e68-999a-218c51b0069c
- Ledger ID: 262b74d1-f6bf-472d-bdfb-3be5ade71968
- Period: APR-2025 (OPEN) · FY 2025-26
- 25 accounts: 1100-1800 (ASSET), 2100-2500 (LIABILITY), 3100-3200 (EQUITY),
  4100-4300 (REVENUE), 5100-5700 (EXPENSE)
- 10 journals posted: JE-2629-00001 through JE-2629-00010
- Expected P&L: Revenue ₹1,00,00,000 · Expenses ₹45,00,000 · Profit ₹55,00,000

### Post-rebuild bootstrap sequence
1. docker rm -f evyoog-postgres && docker run -d --name evyoog-postgres ...
2. mvn spring-boot:run &
3. Create consumption context via SQL (gen_random_uuid())
4. POST /api/v1/gl/setup-wizard/run → get legalEntityId
5. INSERT INTO auth.user_roles for admin@evyoog.com + SYS_ADMIN
6. ./scripts/seed-demo-data.sh <legalEntityId>

### Demo data — Orbinox Valves India Pvt Ltd
- Company: Orbinox Valves India Pvt Ltd (industrial valve manufacturer)
- Legal Entity ID: d53c88e1-1bd0-4e68-999a-218c51b0069c
- Ledger ID: 262b74d1-f6bf-472d-bdfb-3be5ade71968
- Period: APR-2025 (OPEN) · FY 2025-26
- 25 accounts: 1100-1800 (ASSET), 2100-2500 (LIABILITY), 3100-3200 (EQUITY),
  4100-4300 (REVENUE), 5100-5700 (EXPENSE)
- 10 journals posted: JE-2629-00001 through JE-2629-00010
- Expected P&L: Revenue INR 1,00,00,000 · Expenses INR 45,00,000 · Profit INR 55,00,000

### Post-rebuild bootstrap sequence
1. docker rm -f evyoog-postgres && docker run -d --name evyoog-postgres
   -e POSTGRES_DB=evyoog_gl -e POSTGRES_USER=evyoog_app
   -e POSTGRES_PASSWORD=evyoog_dev_pass -p 5432:5432 postgres:16
2. mvn spring-boot:run &
3. Create consumption context via SQL (gen_random_uuid())
4. POST /api/v1/gl/setup-wizard/run -> get legalEntityId
5. INSERT INTO auth.user_roles for admin@evyoog.com + SYS_ADMIN
6. ./scripts/seed-demo-data.sh <legalEntityId>

### GL-17 — AieImportRequest/AieLineRequest are Java records
- These are Java records (NOT mutable beans) — no setters, no @Builder
- Use canonical constructors only
- accountCombination on AieLineRequest is for non-natural-account dimensions only
  Do NOT set { "NATURAL_ACCOUNT": code } — natural account resolved via accountCode field
- Follow ApiResponse<T> envelope on all controllers (not raw DTO return)

### GL-19 — je_source_reference did NOT exist in V1 baseline
- Table was created fresh in V26 migration
- Prompt assumed it existed — it did not

### GL-20 — aie.sla_event_log actual schema (V9 baseline)
- Columns: id, ledger_id, legal_entity_id, accounting_period_id,
  event_payload (JSONB), status, created_at
- NO batch_id, NO journal_header_id, NO event_type column
- Filter by legalEntityId, ledgerId, accountingPeriodId, status only

### GL-06 — coa_import_job already existed in V5 baseline
- Columns differ from prompt assumptions:
  finance_dimension_id (not legal_entity_id)
  error_details (not errors)
  created_by is UUID (not VARCHAR)
  status values: PENDING/PROCESSING/COMPLETED/COMPLETED_WITH_ERRORS/FAILED
  (no PARTIAL status)
- Endpoint is POST /api/v1/gl/coa-import-jobs (not /coa/import)
- CoaImportJob entity, repository, mapper, controller already scaffolded in GL-05

### GL-06 — REQUIRES_NEW transaction isolation (CRITICAL)
- ChartOfAccountsService.createAccount() → DimensionValueService.create()
  is itself @Transactional
- Calling it from inside a @Transactional job method means a single bad row
  marks the whole transaction rollback-only (UnexpectedRollbackException)
- Fix: wrap each row's createAccount() call in a REQUIRES_NEW-scoped service
  method (CoaImportRowService.createAccountIsolated())
- Pattern to follow for ANY import service that calls @Transactional services
  in a loop with per-row error handling

### Demo data — updated Legal Entity ID (July 21, 2026)
- Legal Entity ID: 50612f23-6dfa-423f-8546-25b5bc45a57f
- Previous IDs are stale — use this one for all curl commands

### seed-demo-data.sh — JE-11 removed (year-end close deferred)
- JE-11 closing entry removed from seed script
- Revenue/Expense accounts retain balances so P&L shows real data
- Year-end close (transfer net income to Retained Earnings) is a 
  deferred capability — not yet built
- Script now posts 10 journals only, period ends CLOSED

### Demo data — latest Legal Entity ID (July 22, 2026)
- Legal Entity ID: c6dec054-bbfd-458f-a5dd-f7c7bbb3bc42
- Previous IDs are stale — use this one for all curl commands

### Pending backend capability
- POST /api/v1/auth/change-password — not yet built
  Takes: currentPassword, newPassword
  Updates auth.users.password_hash, sets must_change_pwd=false
  Invalidates existing refresh tokens

### GL-04/GL-15 — multi-dimension account combination (CRITICAL — corrects a
### wrong build-prompt assumption)
- `gl.journal_line.account_combination` JSONB keys are the **`DimensionType`
  enum constant name** (`NATURAL_ACCOUNT`, `COST_CENTRE`, `PRODUCT`, ...) —
  NOT the Finance Dimension's `code` field. `PostingEngine.validateDimensionValues()`
  parses each key via `DimensionType.valueOf(entry.getKey())`; a build prompt
  that says to use the dimension code (e.g. `"COST-CTR"`) as the JSONB key is
  wrong and contradicts this already-tested behaviour — `seed-demo-data.sh`
  and every IT fixture already use `NATURAL_ACCOUNT` as the key while the
  Finance Dimension's own `code` stays `NAT-ACCT`. Do not change this.
- `naturalAccountValueId` is passed to the Posting Engine as its own field on
  `PostingLineRequest`/`CreateJournalLineRequest` — it is never extracted from
  `account_combination`. NATURAL_ACCOUNT is therefore exempt from the
  "required dimension must appear in the combination" rule below; every other
  required dimension type is not.
- Added Rule 9 to `PostingEngine.validateDimensionValues()`: for every active
  Finance Dimension on the Ledger where `isRequired = true` (excluding
  NATURAL_ACCOUNT), each journal line's `account_combination` must contain
  that dimension type as a key, else `400 MISSING_REQUIRED_DIMENSION`.
  Optional dimensions (e.g. PRODUCT) may simply be absent.
- `FinanceDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId)` added
  to load the ledger's dimension set once per validation call.
- Dimension Value CRUD (`DimensionValueController`, `/api/v1/gl/dimension-values`)
  and Finance Dimension CRUD already supported COST_CENTRE/PRODUCT generically
  before this session — no new endpoints were needed, only the missing-required
  -dimension check above.
- Did NOT add an `allow_dynamic_insert` column to `gl.ledger` as one build
  prompt suggested — nothing in scope consumes it (no auto-vivify-on-post
  logic exists), so it would have been a dead column. Add it only alongside
  the feature that reads it.
- `seed-demo-data.sh` now also creates `COST-CTR` (COST_CENTRE, required) and
  `PRODUCT` (PRODUCT, optional) Finance Dimensions + values (CC-MFG/CC-SAL/
  CC-ADM/CC-RND, GATE-VLV/BALL-VLV/BFLY-VLV/SPARES/SERVICES), and all 10 demo
  journals now carry `COST_CENTRE` on every line (`PRODUCT` on revenue lines
  only) — step count is now [1/10]..[10/10].

### GL-04 Multi-Dimension Account Combination (V27 — July 2026)
- account_combination JSONB key = DimensionType enum name (NOT dimension code)
  Correct: {"COST_CENTRE": "CC-MFG", "NATURAL_ACCOUNT": "5100", "PRODUCT": "GATE-VLV"}
  Wrong:   {"COST-CTR": "CC-MFG", "NAT-ACCT": "5100"}
- allow_dynamic_insert NOT added to gl.ledger (deferred — no consumer yet)
- Dimension CRUD was already generic across all types from earlier build
- PostingEngine validates required dimensions present (excludes NATURAL_ACCOUNT
  since that's carried via naturalAccountValueId not the combination map)
- Cost Centre: required dimension, 4 values (CC-MFG, CC-SAL, CC-ADM, CC-RND)
- Product: optional dimension, 5 values (GATE-VLV, BALL-VLV, BFLY-VLV, SPARES, SERVICES)
- Test count: 497 (324 unit + 173 integration)

## AUTH — GET /api/v1/auth/users/{id}/roles fix (July 2026)
- GET handler was missing — only POST and DELETE were registered
- Fixed: GET now returns List<UserRoleAssignmentResponse>
- Permission: gl:users:view
- No new migrations, DTOs or repository methods needed
- UserRoleRepository.findByUserId already existed
- Test count: 324 unit tests passing

## AUTH — change-password error envelope deviation
- POST /api/v1/auth/change-password error response uses:
  {status, code, message, field, timestamp} NOT standard ApiResponse envelope
  Codes: INVALID_CURRENT_PASSWORD | WEAK_PASSWORD

## Account Ledger — accountCombination in response (August 2026)
- AccountLedgerEntry DTO now includes Map<String, String> accountCombination
- Mapped from line.getAccountCombination() in AccountLedgerService
- 324 unit tests passing

## GL-26 — Segment Reporting (August 2026)
- Trial Balance filter: `GET /api/v1/gl/reports/trial-balance` and its
  `/export` sibling take optional `costCentre`/`product` query params.
  `TrialBalanceService.generate(...)` gained a 4-arg overload — the original
  2-arg method now just delegates with nulls, so existing callers/tests are
  unaffected. Filtering uses a new native query
  (`AccountBalanceRepository.findByLegalEntityIdAndAccountingPeriodIdAndCombinationFilter`)
  with the JSONB `@>` containment operator, backed by the existing GIN index.
- **Behavioural split on empty results**: with no filter, zero balances still
  throws `404 NO_BALANCES_FOUND` (unchanged, pre-existing behaviour — signals
  "nothing posted yet"). With a filter applied, zero matches returns
  `200 OK` with an empty `lines[]` (a valid "no activity for this segment"
  result, not an error) — the 404 path is only reachable via the unfiltered
  `findByLegalEntityIdAndAccountingPeriodId` branch.
- New endpoint `GET /api/v1/gl/reports/pl-by-segment` (package
  `com.evyoog.gl.reporting.segment`) pivots Revenue/Expense account_balance
  rows by any Finance Dimension segment (not hardcoded to COST_CENTRE/
  PRODUCT — validated via `DimensionType.valueOf`, with `NATURAL_ACCOUNT`
  explicitly rejected since it's never a JSONB key in `account_combination`).
  Reuses the existing `gl:pl:view` permission — no new permission migration,
  same "one epic, shared permission codes" precedent as Period Management.
- **Postgres GROUP BY pitfall (only caught by the Testcontainers IT, not
  Mockito unit tests)**: the pivot query originally repeated
  `ab.account_combination ->> :segmentType` in both SELECT and GROUP BY.
  Spring Data binds each `:segmentType` occurrence to its own JDBC `?`
  placeholder, and Postgres treats two separately-bound placeholders as
  distinct expression nodes even when the bound value is identical — it
  rejects the query with "column must appear in the GROUP BY clause or be
  used in an aggregate function". Fixed by extracting the segment value once
  in a derived subquery and grouping on the derived column instead. Any
  future native `@Query` that both projects and groups by the same
  parameterized expression must use this derived-subquery pattern, not repeat
  the expression — a pure mocked-repository unit test cannot catch this class
  of bug, only a real-Postgres IT can.
- `account_qualifier` on `dimension_value` reads back as plain text in native
  queries (`@Enumerated(STRING)` column), so `IN ('REVENUE','EXPENSE')` and
  string comparisons against `row.getAccountQualifier()` work without enum
  casting.
- 392 unit tests + full Testcontainers IT suite passing (`mvn verify`).
