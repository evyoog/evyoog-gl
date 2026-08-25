# eVyoog ERP — GL Module Development Instructions
## Complete guide for Claude Code · PLATFORM_FOUNDATION_v2.0 · GL_CORE_v1.0

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture](#2-architecture)
3. [GitHub Codespaces Setup](#3-github-codespaces-setup)
4. [Project Structure](#4-project-structure)
5. [Database and Schema](#5-database-and-schema)
6. [The 29 GL Capabilities](#6-the-29-gl-capabilities)
7. [Build Sequence — Critical Path](#7-build-sequence--critical-path)
8. [How to Build Each Capability](#8-how-to-build-each-capability)
9. [Testing Approach](#9-testing-approach)
10. [Design Rules — Never Violate](#10-design-rules--never-violate)
11. [India Localisation Requirements](#11-india-localisation-requirements)
12. [Three Financial Modes](#12-three-financial-modes)
13. [Capability-by-Capability Build Prompts](#13-capability-by-capability-build-prompts)
14. [Definition of Done](#14-definition-of-done)

---

## 1. Project Overview

**Product**: eVyoog ERP — a capability-based modular ERP platform for Indian discrete manufacturing SMEs.

**What we are building**: The General Ledger (GL) module — 29 capabilities covering enterprise structure, ledger management, chart of accounts, calendar and period control, journal entry and posting, reporting, AIE pipeline, and India localisation.

**Key facts**:
- Architecture: PLATFORM_FOUNDATION_v2.0 — Capability-based Modular
- Tech stack: Java 21, Spring Boot 3, PostgreSQL 16, Flyway, MapStruct, JUnit 5, Testcontainers
- Database: One PostgreSQL database, `gl` schema for all GL capabilities, `aie` schema for AIE pipeline
- Build approach: One capability per Claude Code session. Complete and test before starting the next.
- Reference artefacts: `eVyoog_ADR_v2.0.docx`, `eVyoog_FDD_v2.0.docx`, `evyoog_gl_schema_v2.sql`

---

## 2. Architecture

### PLATFORM_FOUNDATION_v2.0 — Capability-based Modular

**Not microservices.** Each capability is independently designed with a clean API contract, but all GL capabilities share one deployment unit and one PostgreSQL database with schema-level ownership.

| Concept | Definition |
|---|---|
| Module | Schema boundary — `gl` schema owns all GL tables, `aie` schema owns AIE tables |
| Capability | API activation and billing unit — independently activatable by a customer |
| Write isolation | GL service writes ONLY to `gl.*` and `aie.*` — enforced by PostgreSQL GRANT |
| Cross-schema reads | Permitted for reporting — natural SQL JOINs across `gl.*` and `ap.*` (Phase 2) |
| Transactional flows | Events between modules — never direct cross-schema writes |

### Four governing rules

1. **GL service writes only to `gl.*` and `aie.*`** — No other schema, ever
2. **Cross-schema reads are permitted** — for Reporting only, via SQL JOINs, never writes
3. **Transactional flows use events** — no module writes directly to another module's schema
4. **Every capability has its own REST API** — `capability_registry` controls what is active per customer

### Two consumption segments

| Segment | Type | Description |
|---|---|---|
| Segment 1 | WORKSPACE | Persistent, multi-period, subscription billing |
| Segment 2 | SESSION | Ephemeral, TTL-bound, single-period, per-use billing |

### Three financial modes

| Mode | Description |
|---|---|
| THICK | Full double-entry GL, 2–15 Finance Dimensions, subledgers, period-close, all reports |
| THIN | Lightweight double-entry, exactly 2 Finance Dimensions, no subledger, basic P&L |
| EVENT_ONLY | No GL entries — structured SLA events emitted to Kafka, downstream system posts |

---

## 3. GitHub Codespaces Setup

### 3.1 One-time setup — add devcontainer to repo

Add the following file to your repository at `.devcontainer/devcontainer.json`:

```json
{
  "name": "eVyoog GL Development",
  "image": "mcr.microsoft.com/devcontainers/java:21-bullseye",
  "features": {
    "ghcr.io/devcontainers/features/java:1": {
      "version": "21",
      "installMaven": "true",
      "installGradle": "false"
    },
    "ghcr.io/devcontainers/features/docker-in-docker:2": {}
  },
  "services": {
    "postgres": {
      "image": "postgres:16",
      "environment": {
        "POSTGRES_DB": "evyoog_gl",
        "POSTGRES_USER": "evyoog_app",
        "POSTGRES_PASSWORD": "evyoog_dev_pass"
      },
      "ports": ["5432:5432"]
    }
  },
  "forwardPorts": [8080, 5432],
  "postCreateCommand": "mvn dependency:resolve -q || true",
  "customizations": {
    "vscode": {
      "extensions": [
        "vscjava.vscode-java-pack",
        "pivotal.vscode-spring-boot",
        "eamodio.gitlens",
        "humao.rest-client",
        "42crunch.vscode-openapi",
        "mtxr.sqltools",
        "mtxr.sqltools-driver-pg"
      ],
      "settings": {
        "java.compile.nullAnalysis.mode": "automatic",
        "spring-boot.ls.java.home": "/usr/local/sdkman/candidates/java/current",
        "editor.formatOnSave": true,
        "editor.tabSize": 4
      }
    }
  },
  "remoteEnv": {
    "SPRING_DATASOURCE_URL": "jdbc:postgresql://vyg-batch-1.cgtfswn9milw.ap-south-1.rds.amazonaws.com:5432/vygmicroservice",
    "SPRING_DATASOURCE_USERNAME": "postgres",
    "SPRING_DATASOURCE_PASSWORD": "vygpost23"
  }
}
```

### 3.2 How to open Codespaces

1. Go to `github.com/prashantha-vyoog/evyoog-design`
2. Click the green **Code** button
3. Click the **Codespaces** tab
4. Click **Create codespace on main**
5. Wait ~60 seconds — Java 21, Maven, and PostgreSQL are configured automatically
6. The VS Code editor opens in your browser — fully configured

### 3.3 Verify the environment is working

Run these in the Codespaces terminal:

```bash
# Confirm Java 21
java -version

# Confirm Maven
mvn -version

# Confirm PostgreSQL is reachable
psql -h vyg-batch-1.cgtfswn9milw.ap-south-1.rds.amazonaws.com -U postgres -d vygmicroservice -c "SELECT version();"

# Confirm Docker (for Testcontainers)
docker --version
```

All four must return without errors before starting any capability build.

### 3.4 Environment variables

The `devcontainer.json` above sets these automatically. If you need to override locally:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://vyg-batch-1.cgtfswn9milw.ap-south-1.rds.amazonaws.com:5432/vygmicroservice
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=vygpost23
export SPRING_PROFILES_ACTIVE=dev
```

---

## 4. Project Structure

```
evyoog-gl/
├── .devcontainer/
│   └── devcontainer.json          ← Codespaces configuration
├── .github/
│   └── workflows/
│       └── ci-cd.yml              ← GitHub Actions — build, test, deploy to AWS
├── src/
│   ├── main/
│   │   ├── java/com/evyoog/gl/
│   │   │   ├── EvyoogGlApplication.java
│   │   │   ├── config/
│   │   │   │   ├── DataSourceConfig.java
│   │   │   │   ├── FlywayConfig.java
│   │   │   │   └── OpenApiConfig.java
│   │   │   ├── common/
│   │   │   │   ├── audit/
│   │   │   │   │   ├── domain/AuditLog.java
│   │   │   │   │   ├── repository/AuditLogRepository.java
│   │   │   │   │   └── service/AuditService.java   ← shared by ALL capabilities
│   │   │   │   ├── exception/
│   │   │   │   │   ├── EvyoogException.java
│   │   │   │   │   ├── ResourceNotFoundException.java
│   │   │   │   │   ├── DuplicateResourceException.java
│   │   │   │   │   └── GlobalExceptionHandler.java
│   │   │   │   └── response/
│   │   │   │       └── ApiResponse.java
│   │   │   ├── enterprise/                         ← GL-01 Enterprise Setup
│   │   │   │   ├── api/
│   │   │   │   ├── service/
│   │   │   │   ├── repository/
│   │   │   │   ├── domain/
│   │   │   │   ├── dto/
│   │   │   │   └── mapper/
│   │   │   ├── ledger/                             ← GL-03 Ledger Management
│   │   │   ├── dimension/                          ← GL-04 Finance Dimensions
│   │   │   ├── coa/                                ← GL-05 Chart of Accounts
│   │   │   ├── calendar/                           ← GL-08 Accounting Calendar
│   │   │   ├── period/                             ← GL-09 + GL-10 Period Management
│   │   │   ├── journal/                            ← GL-11 through GL-15
│   │   │   ├── aie/                                ← GL-16 through GL-20 AIE Pipeline
│   │   │   ├── balance/                            ← GL-21 Account Balance
│   │   │   ├── reporting/                          ← GL-22 through GL-26
│   │   │   └── localisation/                       ← GL-27 through GL-28
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       └── db/migration/
│   │           ├── V1__gl01_enterprise_setup.sql
│   │           ├── V2__gl02_setup_wizard.sql
│   │           ├── V3__gl03_ledger_management.sql
│   │           └── ... (one migration file per capability)
│   └── test/
│       └── java/com/evyoog/gl/
│           ├── enterprise/
│           ├── ledger/
│           └── ... (mirrors main structure)
├── http/                                           ← REST client test files
│   ├── gl01-enterprise-setup.http
│   ├── gl03-ledger-management.http
│   └── ...
├── pom.xml
└── README.md
```

### 4.1 pom.xml — dependencies

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.0</version>
        <relativePath/>
    </parent>

    <groupId>com.evyoog</groupId>
    <artifactId>evyoog-gl</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>eVyoog GL Module</name>

    <properties>
        <java.version>21</java.version>
        <mapstruct.version>1.5.5.Final</mapstruct.version>
        <testcontainers.version>1.19.8</testcontainers.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Core -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- PostgreSQL -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Flyway -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- MapStruct -->
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
            <version>${mapstruct.version}</version>
        </dependency>
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct-processor</artifactId>
            <version>${mapstruct.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- OpenAPI / Springdoc -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.5.0</version>
        </dependency>

        <!-- Jackson for JSONB / PostgreSQL types -->
        <dependency>
            <groupId>com.vladmihalcea</groupId>
            <artifactId>hibernate-types-60</artifactId>
            <version>2.21.1</version>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.2 application.yml

```yaml
spring:
  application:
    name: evyoog-gl
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://vyg-batch-1.cgtfswn9milw.ap-south-1.rds.amazonaws.com:5432/vygmicroservice}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:vygpost23}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate        # Flyway manages schema — JPA only validates
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        default_schema: gl
        format_sql: true
  flyway:
    enabled: true
    schemas: gl, aie
    locations: classpath:db/migration
    baseline-on-migrate: false
    validate-on-migrate: true

springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
    enabled: true

server:
  port: 8080
  servlet:
    context-path: /

logging:
  level:
    com.evyoog: INFO
    org.flywaydb: INFO
```

---

## 5. Database and Schema

### 5.1 Overview

- **Database name**: `vygmicroservice` (shared RDS instance `vyg-batch-1`, ap-south-1)
- **Schemas**: `gl` (25 tables), `aie` (6 tables), `auth`
- **Total**: 31 tables, 92 indexes, 3 views
- **Migrations**: Flyway, one SQL file per capability, versioned V1, V2, V3...
- **Schema file**: `evyoog_gl_schema_v2.sql` — the complete baseline

### 5.2 Table map by capability

| Capability | Tables |
|---|---|
| GL-01 Enterprise Setup | `gl.consumption_context`, `gl.business_group`, `gl.legal_entity`, `gl.business_unit`, `gl.inventory_organisation`, `gl.sub_inventory`, `gl.audit_log` |
| GL-03 Ledger Management | `gl.ledger`, `gl.legal_entity_ledger` |
| GL-04 Finance Dimensions | `gl.finance_dimension`, `gl.dimension_value` |
| GL-05 CoA Management | `gl.dimension_value` (Natural Account type), `gl.provisioning_template` |
| GL-06 CoA Import | `gl.coa_import_job` |
| GL-08 Accounting Calendar | `gl.accounting_calendar` |
| GL-09 Period Management | `gl.accounting_period` |
| GL-10 Period Status Control | `gl.period_status` |
| GL-11 Manual Journal Entry | `gl.journal_source`, `gl.journal_category`, `gl.journal_header`, `gl.journal_line` |
| GL-12 Journal Approval | `gl.journal_approval_log` |
| GL-14 Recurring Journals | `gl.recurring_journal_template` |
| GL-15 Posting Engine | Updates `gl.account_balance` |
| GL-16 AIE REST Import | `aie.interface_batch`, `aie.interface_line`, `aie.interface_error`, `aie.deduplication_log` |
| GL-19 Source Reference | `aie.je_source_reference`, `aie.sla_event_log` |
| GL-21 Account Balance | `gl.account_balance` |
| GL-29 Audit Trail | `gl.audit_log` (shared by all capabilities) |
| Platform | `gl.capability_registry`, `gl.context_capability` |

### 5.3 JSONB handling

`journal_line.account_combination` and `gl.account_balance.account_combination` are JSONB columns. Use `hibernate-types-60` for mapping:

```java
@Type(JsonBinaryType.class)
@Column(name = "account_combination", columnDefinition = "jsonb")
private Map<String, String> accountCombination;
```

### 5.4 First Flyway migration — V1

The very first migration `V1__baseline.sql` should contain the entire `evyoog_gl_schema_v2.sql` content — all 31 tables, indexes, and views. Subsequent migrations add only what changes per capability build (typically nothing — the schema is already complete).

---

## 6. The 29 GL Capabilities

### Group 1 — Enterprise Structure
| Code | Capability | Depends on | WS | SE |
|---|---|---|---|---|
| GL-01 | Enterprise Setup | Platform foundation | ✓ | ✓ |
| GL-02 | Setup Wizard | GL-01 | ✓ | ✓ |

### Group 2 — Ledger and CoA
| Code | Capability | Depends on | WS | SE |
|---|---|---|---|---|
| GL-03 | Ledger Management | GL-01 | ✓ | ✓ |
| GL-04 | Finance Dimension Management | GL-03 | ✓ | ✓ |
| GL-05 | Chart of Accounts Management | GL-04 | ✓ | ✓ |
| GL-06 | CoA Excel Import | GL-05 | ✓ | ✓ |
| GL-07 | Industry CoA Templates | GL-04 | ✓ | ✓ |

### Group 3 — Calendar and Period
| Code | Capability | Depends on | WS | SE |
|---|---|---|---|---|
| GL-08 | Accounting Calendar | GL-03 | ✓ | ✓ |
| GL-09 | Period Management | GL-08 | ✓ | ✓ |
| GL-10 | Period Status Control | GL-09 (Thick+Thin only) | ✓ | ✓ |

### Group 4 — Journal Entry and Posting
| Code | Capability | Depends on | WS | SE |
|---|---|---|---|---|
| GL-11 | Manual Journal Entry | GL-05 + GL-10 | ✓ | ✓ |
| GL-12 | Journal Approval | GL-11 | ✓ | ✓ |
| GL-13 | Journal Reversal | GL-11 (Thick only) | – | ✓ |
| GL-14 | Recurring Journals | GL-11 (Thick only) | – | ✓ |
| GL-15 | Posting Engine | GL-05 + GL-09 | ✓ | ✓ |

### Group 5 — AIE Pipeline
| Code | Capability | Depends on | WS | SE |
|---|---|---|---|---|
| GL-16 | AIE REST Journal Import | GL-15 | ✓ | ✓ |
| GL-17 | AIE Excel/CSV Import | GL-15 | ✓ | ✓ |
| GL-18 | AIE Kafka SLA Ingest | GL-15 (Phase 2) | – | ✓ |
| GL-19 | AIE Source Reference | GL-16 | ✓ | ✓ |
| GL-20 | Event Emission (Event-only) | GL-08 + GL-09 | ✓ | ✓ |

### Group 6 — Balance and Reporting
| Code | Capability | Depends on | WS | SE |
|---|---|---|---|---|
| GL-21 | Account Balance Maintenance | GL-15 | ✓ | ✓ |
| GL-22 | Trial Balance | GL-21 | ✓ | ✓ |
| GL-23 | Profit and Loss Statement | GL-22 (Thick+Thin) | ✓ | ✓ |
| GL-24 | Balance Sheet | GL-23 (Thick only) | – | ✓ |
| GL-25 | Cash Flow Statement | GL-24 (Thick only) | – | ✓ |
| GL-26 | Account Ledger + Listing | GL-11 | ✓ | ✓ |

### Group 7 — India Localisation
| Code | Capability | Depends on | WS | SE |
|---|---|---|---|---|
| GL-27 | GST Flagging + GSTR Export | GL-11 | ✓ | ✓ |
| GL-28 | TDS/TCS Recording | GL-11 | ✓ | ✓ |

### Group 8 — Audit and Governance
| Code | Capability | Depends on | WS | SE |
|---|---|---|---|---|
| GL-29 | Audit Trail | GL-01 (always on) | ✓ | ✓ |

---

## 7. Build Sequence — Critical Path

**Rule: complete and merge each capability before starting the next.**

### Critical path — GL to Trial Balance (12 capabilities)

```
GL-01  Enterprise Setup       → No dependencies. Start here.
  ↓
GL-02  Setup Wizard           → Wraps GL-01
  ↓
GL-03  Ledger Management      → Requires GL-01
  ↓
GL-04  Finance Dimensions     → Requires GL-03
  ↓
GL-05  Chart of Accounts      → Requires GL-04
  ↓
GL-08  Accounting Calendar    → Requires GL-03
  ↓
GL-09  Period Management      → Requires GL-08
  ↓
GL-10  Period Status Control  → Requires GL-09
  ↓
GL-15  Posting Engine         → Requires GL-05 + GL-09
  ↓
GL-11  Manual Journal Entry   → Requires GL-05 + GL-10 + GL-15
  ↓
GL-21  Account Balance        → Requires GL-15 (atomic with posting)
  ↓
GL-22  Trial Balance          ← 🎯 FIRST MAJOR MILESTONE
```

### After the critical path

```
Reporting:     GL-23 → GL-24 → GL-25 → GL-26
Workflow:      GL-12 → GL-13 → GL-14
AIE:           GL-16 → GL-17 → GL-19 → GL-20
CoA utilities: GL-06 → GL-07
Localisation:  GL-27 → GL-28
Audit:         GL-29 (always on with GL-01, formalised here)
Phase 2:       GL-18 (Kafka — when subledgers exist)
```

---

## 8. How to Build Each Capability

### The two-tool workflow

**Claude.ai** (this conversation): Design session before each capability. Resolve open questions, generate the filled Claude Code prompt, agree acceptance criteria.

**Claude Code in Codespaces**: Implementation session. Paste the prompt, generate code, run tests, validate API, push to GitHub.

### The 5-step lifecycle (same for every capability)

```
Step 1 — Design (Claude.ai, ~15 min)
         Review FDD section for this capability
         Resolve any open design questions
         Get the filled Claude Code prompt

Step 2 — Build (Claude Code, 2-4 hrs)
         Paste the prompt
         Claude Code generates: entities, repos, services, controllers, DTOs, tests
         You review — you are the domain SME

Step 3 — Test (inside Codespaces)
         mvn test          → unit tests pass
         mvn verify        → integration tests pass (Testcontainers + real PostgreSQL)
         API test via port-forwarded URL from Postman

Step 4 — Domain SME review (you)
         Validate API responses match business expectations
         Confirm edge cases work correctly (e.g. Thin ES rejecting second LE)
         Any issue → back to Step 2
         Everything correct → Step 5

Step 5 — Merge and deploy
         git push → GitHub Actions builds Docker image → deploys to AWS DEV
         capability_registry row confirmed active
         Prepare next capability prompt
```

### Per-capability implementation checklist

For every capability, Claude Code must produce:

- [ ] Flyway migration SQL file (Vn__glXX_capability_name.sql)
- [ ] JPA entity classes with correct annotations and relationships
- [ ] Spring Data JPA repositories
- [ ] Service layer with all business validation rules
- [ ] REST controllers with OpenAPI/Springdoc annotations
- [ ] Request and Response DTOs (never expose entities directly)
- [ ] MapStruct mappers (entity ↔ DTO)
- [ ] Unit tests for all service-layer business rules (mock repositories)
- [ ] Integration tests using Testcontainers with real PostgreSQL
- [ ] `.http` file with example requests for all endpoints
- [ ] `audit_log` entry written for every write operation in the same transaction

---

## 9. Testing Approach

### Three levels

**Level 1 — Unit tests** (`mvn test`)
- No database required
- Mock all repositories with Mockito
- Test every business rule in isolation
- Example: `testThinEsRejectsSecondLegalEntity()`, `testGstinFormatValidation()`

**Level 2 — Integration tests** (`mvn verify`)
- Testcontainers spins up real PostgreSQL 16
- Flyway migration runs automatically
- All endpoint tests run against a real database
- Tests are annotated with `@Testcontainers` and `@SpringBootTest`

**Level 3 — API tests** (Postman / `.http` files)
- Spring Boot app running: `mvn spring-boot:run`
- Codespaces auto-forwards port 8080 → public HTTPS URL
- Hit live running APIs from Postman on your laptop

### Standard test class template

```java
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class BusinessGroupControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("evyoog_gl_test")
            .withUsername("evyoog_app")
            .withPassword("evyoog_test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createBusinessGroup_success() throws Exception {
        // given
        String request = """
            {
                "contextId": "...",
                "code": "BG-001",
                "name": "Coimbatore Manufacturing Group",
                "esMode": "THICK_ES",
                "defaultCurrency": "INR"
            }
            """;

        // when / then
        mockMvc.perform(post("/api/v1/gl/business-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("BG-001"));
    }
}
```

---

## 10. Design Rules — Never Violate

These rules apply to every capability without exception:

### Rule 1 — Write isolation
The GL service writes ONLY to `gl.*` and `aie.*` tables. No other schema, ever. Enforced by a dedicated PostgreSQL role with GRANT only on these schemas.

### Rule 2 — UUID primary keys
All primary keys are UUIDs generated by `uuid_generate_v4()`. Never use auto-increment integers.

### Rule 3 — Audit every write
Every `CREATE`, `UPDATE`, and soft `DELETE` on any entity writes an `audit_log` row in the **same transaction**. The `AuditService` is built in GL-01 and reused by all subsequent capabilities.

### Rule 4 — Soft delete only
Never execute `DELETE FROM gl.*`. Set `is_active = FALSE`. Audit log records action as `'DELETE'`.

### Rule 5 — Thin ES constraint
A Business Group with `es_mode = THIN_ES` may have exactly one Legal Entity. Reject a second LE creation attempt with `HTTP 409` and a clear error message. Enforced at service layer — not just documentation.

### Rule 6 — Thin finance mode constraint
A Ledger with `finance_mode = THIN` must have exactly 2 Finance Dimensions: `LEGAL_ENTITY` and `NATURAL_ACCOUNT`. Reject a third dimension. Enforced at service layer.

### Rule 7 — Period gate on posting
Every journal posting validates that the target `accounting_period` is in `OPEN` status for the journal's `legal_entity_id`. Reject posting to a closed or locked period with `HTTP 422`.

### Rule 8 — finance_mode_snapshot is immutable
`journal_header.finance_mode_snapshot` is written once at journal creation from `ledger.finance_mode`. Never update it. All reporting and historical queries use `finance_mode_snapshot` — never the current `ledger.finance_mode`.

### Rule 9 — Codified dimension values
All Finance Dimension values must exist in `gl.dimension_value` before use. Free-text dimension values are explicitly forbidden. Validate before posting — return `HTTP 422` if any value does not exist.

### Rule 10 — Idempotency in AIE
Before processing any import, check `aie.deduplication_log` for the `event_id`. Reject duplicates immediately with `HTTP 409`. Write the deduplication log entry at Stage 1 before any processing.

### Rule 11 — GSTIN format validation
Validate GSTIN against the 15-character Indian format before persisting:
`^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$`
Return `HTTP 400` with a clear field-level error if invalid.

### Rule 12 — Never expose JPA entities in API responses
All API responses use DTOs. Map entities to DTOs using MapStruct. Never return a JPA entity directly from a controller.

---

## 11. India Localisation Requirements

### GST — Goods and Services Tax

| Field | Location | Values |
|---|---|---|
| `gstin` | `gl.business_unit` | 15-char GSTIN (unique per BU) |
| `state_code` | `gl.business_unit` | 2-digit prefix from GSTIN |
| `gst_applicable` | `gl.journal_line` | boolean |
| `gst_type` | `gl.journal_line` | CGST, SGST, IGST, UTGST |

**Intra-state**: CGST + SGST — same BU state
**Inter-state**: IGST — different BU states or different LEs
**1 BU = 1 State = 1 GSTIN** — this is the correct Indian model. Never attach GSTIN to Legal Entity.

### TDS/TCS — Tax Deducted/Collected at Source

| Field | Location | Values |
|---|---|---|
| `tds_applicable` | `gl.journal_line` | boolean |
| `tds_section` | `gl.journal_line` | 194C, 194J, 194H, 192, etc. |
| `tan` | `gl.legal_entity` | Tax Deduction Account Number |

### Fiscal year

- April 1 to March 31
- Period naming: `APR-2025`, `MAY-2025`, ..., `MAR-2026`
- Fiscal year name format: `2025-26`
- Default calendar auto-created by Setup Wizard with April start

### Accounting standards

Set at Legal Entity level: `IND_AS` (default), `IGAAP`, `IFRS`, `US_GAAP`

---

## 12. Three Financial Modes

Every capability must be aware of `finance_mode`. The Posting Engine (GL-15) reads it once per posting operation and branches. No other service should have `finance_mode` branching logic scattered through it.

| Behaviour | THICK | THIN | EVENT_ONLY |
|---|---|---|---|
| Journal entries created | Yes — full double-entry | Yes — lightweight | No — events only |
| Finance Dimensions | 2–15 | Exactly 2 | None required |
| Period status gate | Yes — OPEN required | Yes — OPEN required | No (Phase 1) |
| Balance accumulation | Yes — `account_balance` | Yes — `account_balance` | No |
| Period-close workflow | Full (7 steps) | Simplified | None |
| Trial Balance | Full | Simplified | Not applicable |
| Balance Sheet | Yes | No | Not applicable |
| GSTR data | Yes | Partial | From event log |
| Reversal journals | Yes | No | Not applicable |
| Recurring journals | Yes | No | Not applicable |

### EVENT_ONLY specifics

- No `gl.journal_header` or `gl.journal_line` rows created
- No `gl.account_balance` updates
- No `gl.period_status` rows in Phase 1 (calendar exists for event tagging only)
- SLA event payload emitted to Kafka topic `gl.sla.events`
- `aie.sla_event_log` row written for every event
- Transactional layer only — no accounting layer

---

## 13. Capability-by-Capability Build Prompts

### How to use these prompts

Each section below is a self-contained prompt for one Claude Code session. Before starting a capability:

1. Confirm all dependencies are built and tested
2. Copy the prompt for the next capability
3. Open Claude Code in Codespaces
4. Paste the prompt as the first message
5. Follow the 12-step build sequence at the end of every prompt

---

### GL-01 — Enterprise Setup

**Capability**: GL-01 Enterprise Setup
**Depends on**: Platform foundation only
**Tables**: `gl.consumption_context`, `gl.business_group`, `gl.legal_entity`, `gl.business_unit`, `gl.inventory_organisation`, `gl.sub_inventory`, `gl.audit_log`
**Flyway migration**: `V1__baseline.sql` — contains the full `evyoog_gl_schema_v2.sql`

**Business rules**:
- Thin ES Business Group: reject second Legal Entity with HTTP 409
- GSTIN: validate 15-character format before persisting
- Soft delete only on all five hierarchy entities
- Every write → audit_log in same transaction
- Inventory Organisation is optional under Thin ES

**API surface**:
```
POST/GET  /api/v1/gl/consumption-contexts
POST/GET  /api/v1/gl/business-groups
POST/GET/PATCH  /api/v1/gl/legal-entities
POST/GET/PATCH  /api/v1/gl/business-units
POST/GET  /api/v1/gl/inventory-organisations
POST/GET  /api/v1/gl/sub-inventories
GET       /api/v1/gl/audit-log
```

---

### GL-02 — Setup Wizard

**Capability**: GL-02 Setup Wizard
**Depends on**: GL-01
**Tables**: `gl.consumption_context.provisioning_answers` (JSONB update), `gl.provisioning_template`

**Business rules**:
- Maximum 6 questions: company name/structure, business type, states, start period, CoA source, session purpose
- Answers stored in `consumption_context.provisioning_answers` JSONB
- Auto-provisions: BG, LE, BU(s) with GSTINs, Ledger (finance_mode derived from answers), Calendar (Apr-Mar), first Period (OPEN)
- Thin ES + Thin mode coupled silently when business answers indicate single simple entity
- Wizard runs once — returns 409 if provisioning_answers already populated
- Steps aside permanently after provisioning

**API surface**:
```
POST  /api/v1/gl/setup-wizard/start
GET   /api/v1/gl/setup-wizard/status/{contextId}
POST  /api/v1/gl/setup-wizard/provision
GET   /api/v1/gl/provisioning-templates
```

---

### GL-03 — Ledger Management

**Capability**: GL-03 Ledger Management
**Depends on**: GL-01
**Tables**: `gl.ledger`, `gl.legal_entity_ledger`

**Business rules**:
- Each Legal Entity may have exactly 1 PRIMARY Ledger (partial unique index on legal_entity_ledger)
- Ledger is independent from LE — can be shared across multiple LEs
- `finance_mode` (THICK|THIN|EVENT_ONLY) set at Ledger creation — drives all posting behaviour
- `finance_mode` can be upgraded (THIN→THICK) but not downgraded
- Mandatory LE Finance Dimension isolates postings per LE within a shared Ledger
- Ledger categories: PRIMARY (default), SECONDARY, REPORTING, ENCUMBRANCE

**API surface**:
```
POST/GET/PATCH  /api/v1/gl/ledgers
POST/GET        /api/v1/gl/legal-entity-ledgers
PATCH           /api/v1/gl/ledgers/{id}/upgrade-mode
```

---

### GL-04 — Finance Dimension Management

**Capability**: GL-04 Finance Dimension Management
**Depends on**: GL-03
**Tables**: `gl.finance_dimension`, `gl.dimension_value`

**Business rules**:
- Minimum 2 dimensions per Ledger (LEGAL_ENTITY + NATURAL_ACCOUNT — both mandatory)
- Maximum 15 dimensions per Ledger (Thick mode)
- Thin mode: exactly 2 dimensions — reject a third with HTTP 409
- Exactly 1 LEGAL_ENTITY type and exactly 1 NATURAL_ACCOUNT type per Ledger (unique index)
- Dimension values are codified — no free text
- Dimension types: LEGAL_ENTITY, NATURAL_ACCOUNT, COST_CENTRE, PROFIT_CENTRE, INTERCOMPANY, PRODUCT, PROJECT, CUSTOM

**API surface**:
```
POST/GET        /api/v1/gl/finance-dimensions
POST/GET/PATCH  /api/v1/gl/dimension-values
GET             /api/v1/gl/dimension-values/search
```

---

### GL-05 — Chart of Accounts Management

**Capability**: GL-05 Chart of Accounts Management
**Depends on**: GL-04
**Tables**: `gl.dimension_value` (NATURAL_ACCOUNT type — parent-child hierarchy)

**Business rules**:
- Natural Account IS a Finance Dimension value with type = NATURAL_ACCOUNT
- Parent-child hierarchy enforced: parent and child must share the same `account_qualifier`
- Account qualifiers: Asset, Liability, Equity, Revenue, Expense, Budgetary
- `is_summary = TRUE` → rollup node, cannot receive journal postings (enforce on posting)
- `is_postable = TRUE` → leaf node, valid for journal lines
- `normal_balance`: DR for Asset/Expense, CR for Liability/Equity/Revenue
- `gst_applicable` and `tds_applicable` flags per account

**API surface**:
```
POST/GET/PATCH  /api/v1/gl/chart-of-accounts
GET             /api/v1/gl/chart-of-accounts/{ledgerId}/hierarchy
GET             /api/v1/gl/chart-of-accounts/search
```

---

### GL-08 — Accounting Calendar

**Capability**: GL-08 Accounting Calendar
**Depends on**: GL-03
**Tables**: `gl.accounting_calendar`

**Business rules**:
- 1 Accounting Calendar per Ledger
- Default: April start, March end, 12 monthly periods (Indian fiscal year)
- Period types: REGULAR (monthly), ADJUSTMENT (year-end), YEAR_END
- Calendar is the cascade source for all subledgers via FK reference on `accounting_period`
- Fiscal year name format: `2025-26` for April 2025 to March 2026

**API surface**:
```
POST/GET  /api/v1/gl/accounting-calendars
```

---

### GL-09 — Period Management

**Capability**: GL-09 Period Management
**Depends on**: GL-08
**Tables**: `gl.accounting_period`

**Business rules**:
- Periods named: APR-2025, MAY-2025 ... MAR-2026
- Auto-generate 12 monthly periods when calendar is created
- `quarter_number`: Q1=Apr-Jun, Q2=Jul-Sep, Q3=Oct-Dec, Q4=Jan-Mar
- `period_type`: REGULAR (default), ADJUSTMENT, YEAR_END
- All subledger capabilities reference `gl.accounting_period` via FK — this IS the cascade

**API surface**:
```
GET   /api/v1/gl/accounting-calendars/{id}/periods
GET   /api/v1/gl/accounting-periods/{id}
POST  /api/v1/gl/accounting-calendars/{id}/periods/generate
```

---

### GL-10 — Period Status Control

**Capability**: GL-10 Period Status Control
**Depends on**: GL-09 (Thick and Thin modes only)
**Tables**: `gl.period_status`

**Business rules**:
- Period status at Legal Entity level — not Ledger level
- Multiple LEs sharing a Ledger open/close their periods independently
- Thick status lifecycle: NOT_OPENED → FUTURE_ENTERABLE → OPEN → CLOSED → LOCKED
- Thin status lifecycle: NOT_OPENED → OPEN → CLOSED (no FUTURE_ENTERABLE or LOCKED)
- EVENT_ONLY: no `period_status` rows created in Phase 1
- Transitions are one-way — cannot revert CLOSED to OPEN without a senior override (log in audit_trail)
- Posting engine checks period is OPEN for the journal's LE before every post

**API surface**:
```
POST  /api/v1/gl/period-status
GET   /api/v1/gl/period-status?legalEntityId=&periodId=
POST  /api/v1/gl/period-status/{id}/open
POST  /api/v1/gl/period-status/{id}/close
POST  /api/v1/gl/period-status/{id}/lock
```

---

### GL-15 — Posting Engine

**Capability**: GL-15 Posting Engine
**Depends on**: GL-05 + GL-09
**Tables**: Updates `gl.journal_header`, `gl.journal_line`, `gl.account_balance`
**Note**: This is a service-layer capability with no dedicated API. It is called internally by GL-11 (Manual Journal Entry) and GL-16 (AIE REST Import).

**Business rules — 8 posting validation rules applied in sequence**:
1. Sum of all debit lines = sum of all credit lines (DR = CR — journal is balanced)
2. Target `accounting_period` is OPEN for the journal's `legal_entity_id`
3. Every account in `account_combination` exists in CoA for this Ledger
4. Every account is postable (`is_postable = TRUE`, `is_summary = FALSE`)
5. All Finance Dimension values exist in `dimension_value` master
6. LE and BU are authorised to post to this Ledger
7. Currency is valid; exchange rate provided if different from functional currency
8. If source requires approval, `approval_status = APPROVED`

**Mode branching (read `finance_mode` once, then branch)**:
- THICK: all 8 rules, full account_combination validation, `account_balance` updated atomically, `finance_mode_snapshot = THICK`
- THIN: rules 1-6, lightweight balance update, `finance_mode_snapshot = THIN`
- EVENT_ONLY: no journal created, SLA event emitted to Kafka `gl.sla.events`, `sla_event_log` written, `finance_mode_snapshot` not applicable

---

### GL-11 — Manual Journal Entry

**Capability**: GL-11 Manual Journal Entry
**Depends on**: GL-05 + GL-10 + GL-15
**Tables**: `gl.journal_source`, `gl.journal_category`, `gl.journal_header`, `gl.journal_line`

**Business rules**:
- Journal number format: `JE-YYWW-NNNNN` (year, week, sequence)
- Status lifecycle: DRAFT → PENDING_APPROVAL → APPROVED → POSTED → REVERSED/CANCELLED/FAILED
- `account_combination` stored as JSONB: `{"LE": "LE001", "ACCT": "5200", "CC": "CC-001"}`
- `natural_account_value_id` FK stored separately for indexed joins
- Only one of debit/credit per line — never both; amount must be > 0
- Posting delegates to GL-15 Posting Engine
- GST flags: `gst_applicable`, `gst_type` (CGST/SGST/IGST/UTGST)
- TDS flags: `tds_applicable`, `tds_section` (194C, 194J, etc.)

**API surface**:
```
POST        /api/v1/gl/journals
GET         /api/v1/gl/journals/{id}
GET         /api/v1/gl/journals?legalEntityId=&status=&periodId=
PATCH       /api/v1/gl/journals/{id}
POST        /api/v1/gl/journals/{id}/submit
POST        /api/v1/gl/journals/{id}/post
POST        /api/v1/gl/journals/{id}/cancel
GET         /api/v1/gl/journal-sources
GET         /api/v1/gl/journal-categories
```

---

### GL-21 — Account Balance Maintenance

**Capability**: GL-21 Account Balance Maintenance
**Depends on**: GL-15 (updated atomically during every posting)
**Tables**: `gl.account_balance`

**Business rules**:
- Updated inside the GL-15 posting transaction — never out-of-sync with journals
- Stores: `beginning_balance`, `period_to_date_dr`, `period_to_date_cr`, `year_to_date_dr`, `year_to_date_cr` per account combination + period
- `account_combination` JSONB matches journal_line structure
- On period close: Balance Sheet accounts carry forward as `beginning_balance` in next period; P&L accounts reset to zero
- Source of truth for all financial reports

**API surface**:
```
GET  /api/v1/gl/account-balances?legalEntityId=&periodId=&accountCode=
```

---

### GL-22 — Trial Balance

**Capability**: GL-22 Trial Balance
**Depends on**: GL-21
**Tables**: Reads `gl.account_balance` — uses `v_trial_balance` view

**Business rules**:
- Available for THICK and THIN modes only
- Period-to-date and year-to-date columns
- Shows all postable accounts with non-zero balances
- DR and CR columns — sum must balance (DR total = CR total)
- Filterable by period, legal entity, cost centre
- Export to PDF and Excel

**API surface**:
```
GET  /api/v1/gl/reports/trial-balance?legalEntityId=&periodId=
GET  /api/v1/gl/reports/trial-balance/export?format=PDF|EXCEL
```

---

### GL-16 — AIE REST Journal Import

**Capability**: GL-16 AIE REST Journal Import
**Depends on**: GL-15
**Tables**: `aie.interface_batch`, `aie.interface_line`, `aie.interface_error`, `aie.deduplication_log`

**4-stage pipeline**:
1. **Ingest**: Receive REST payload, write to `aie.interface_batch` + `aie.interface_line`, check `aie.deduplication_log` for duplicate `event_id`, set `import_transport = REST`
2. **Validate**: Balance check, period open, account exists and postable, all dimension values valid, LE authorised. Errors → `aie.interface_error` with `error_code` and line number
3. **Enrich**: Resolve account codes to IDs, derive missing dimension values, set `gl_date`, apply GST/TDS flags, link to `accounting_period_id`
4. **Post**: Call GL-15 Posting Engine, write `gl.journal_header` + `gl.journal_line`, update `gl.account_balance`, write `aie.sla_event_log` acknowledgement

**API surface**:
```
POST  /api/v1/aie/journals              ← ingest endpoint
GET   /api/v1/aie/batches/{batchId}    ← status check
GET   /api/v1/aie/batches/{id}/errors  ← error report
POST  /api/v1/aie/batches/{id}/resubmit
```

---

### GL-27 — GST Flagging and GSTR Export

**Capability**: GL-27 GST Flagging + GSTR Export
**Depends on**: GL-11
**Tables**: Reads `gl.journal_line` (gst_type, gst_applicable), `gl.business_unit` (gstin, state_code)

**Business rules**:
- GSTR-1: outward supplies — journal lines where `gst_type IS NOT NULL` on revenue accounts
- GSTR-3B: net liability — `CGST Payable + SGST Payable + IGST Payable` minus input credit accounts
- Data filtered by `business_unit_id` (each BU = one GSTIN = one state registration)
- Export format: GSTN-compatible JSON for filing

**API surface**:
```
GET  /api/v1/gl/gst/gstr1?businessUnitId=&periodId=
GET  /api/v1/gl/gst/gstr3b?businessUnitId=&periodId=
GET  /api/v1/gl/gst/export?type=GSTR1|GSTR3B&businessUnitId=&periodId=
```

---

### GL-28 — TDS/TCS Recording

**Capability**: GL-28 TDS/TCS Recording
**Depends on**: GL-11
**Tables**: Reads `gl.journal_line` (tds_applicable, tds_section), `gl.legal_entity` (tan)

**Business rules**:
- TDS sections: 194C (contractors), 194J (professionals), 194H (commission), 192 (salary)
- TDS journal pattern: Vendor CR (gross) + Expense DR (gross) + TDS Payable CR (deducted amount) + Vendor DR (net)
- TCS: collected from customers, TCS Payable account
- TAN stored on `gl.legal_entity`

**API surface**:
```
GET  /api/v1/gl/tds/summary?legalEntityId=&periodId=
GET  /api/v1/gl/tds/export?legalEntityId=&periodId=
```

---

## 14. Definition of Done

A capability is **done** when ALL of the following are true:

### Code quality
- [ ] No compilation errors or warnings
- [ ] No hardcoded values — all config in `application.yml` or environment variables
- [ ] No JPA entities exposed directly in API responses — always use DTOs
- [ ] MapStruct mappers generated and working

### Tests
- [ ] `mvn test` passes with zero failures (unit tests)
- [ ] `mvn verify` passes with zero failures (integration tests with Testcontainers)
- [ ] All business rules covered by at least one test including the rejection cases
- [ ] Test coverage > 80% on service layer

### API
- [ ] All endpoints return correct HTTP status codes (200, 201, 400, 404, 409, 422)
- [ ] Error responses are structured: `{ "status": 400, "code": "THIN_ES_LE_LIMIT", "message": "..." }`
- [ ] OpenAPI spec generated and accessible at `/swagger-ui.html`
- [ ] `.http` file covers all endpoints with example payloads

### Database
- [ ] Flyway migration runs cleanly on a fresh database
- [ ] All constraints, indexes, and FKs match `evyoog_gl_schema_v2.sql`
- [ ] Audit log entry written for every write operation

### Business rules
- [ ] All rules specific to this capability enforced and tested
- [ ] Finance mode branching correctly implemented where applicable
- [ ] Thin ES constraints enforced where applicable

### Deployment
- [ ] `git push` triggers GitHub Actions without failure
- [ ] Docker image built and pushed to ECR
- [ ] Deployed to AWS DEV environment and API is reachable
- [ ] `gl.capability_registry` row for this capability confirmed active

---

*eVyoog ERP · GL Module Development Instructions · PLATFORM_FOUNDATION_v2.0 · GL_CORE_v1.0*
*Reference: eVyoog_ADR_v2.0.docx · eVyoog_FDD_v2.0.docx · evyoog_gl_schema_v2.sql*
