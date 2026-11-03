# FRD: Multi-tenancy and organization hierarchy for Finance (GL)

| Item | Value |
|---|---|
| Feature | `multi-tenancy-and-org-hierarchy` |
| Product | eVyoog Finance (GL), repository `evyoog/evyoog-gl`; frontend `evyoog/evyoog-frontend` |
| Status | **Draft for Domain SME review.** Nothing in this document is built in Finance yet |
| Date | 3 November 2026 |
| Source of the model | The platform concept as built in EIS, Agile Planner and Macro: [platform-reference-model.md](../../../08-architecture/multi-tenancy/platform-reference-model.md) |
| Starting point in Finance | [finance-current-state.md](../../../08-architecture/multi-tenancy/finance-current-state.md) |
| Hierarchy decision | Group → Legal Entity → Business Unit: [hierarchy-mapping.md](../../../08-architecture/multi-tenancy/hierarchy-mapping.md) |
| Plan | [gap-analysis-and-adoption-plan.md](../../../08-architecture/multi-tenancy/gap-analysis-and-adoption-plan.md) |
| Companion documents | [business-rules.md](business-rules.md), [api-requirements.md](api-requirements.md), [ui-requirements.md](ui-requirements.md), [acceptance-criteria.md](acceptance-criteria.md), [workflows](../../../04-workflows/multi-tenancy-and-org-hierarchy/README.md) |

## 1. Summary

Finance is being enhanced as a service of the eVyoog platform. It must behave like Agile Planner and Macro: a customer is a **group**; each group has its **own database** and Finance has its **own schema** in it; Finance learns who is in the group, and its structure, from the platform by a **signed message**; people sign in **once** at the platform; and what a person may see depends on **who they are and where they sit** in the hierarchy.

The hierarchy used in Finance is **Group → Legal Entity → Business Unit**. The platform's organization is the Legal Entity and the platform's business-unit node is the Business Unit. Finance keeps its own accounting levels below that (Inventory Organisation, Sub-inventory) and its own accounting attributes on every level.

Today Finance has none of this: one shared database, its own users and passwords, its own JWT, no tenant concept, and data endpoints that trust the Legal Entity id a caller sends. This FRD defines what must be true when the enhancement is done.

## 2. Goals

1. Two customers can use Finance at the same time with no way to see or change each other's data.
2. A customer is created, changed, suspended and stopped by the platform, with no hand-written SQL.
3. People are created and removed by the platform; Finance never holds a password.
4. A person sees only the Legal Entities and Business Units they belong to or administer.
5. Every write is traceable to a real signed-in person.
6. Finance's accounting rules (ledgers, periods, posting, audit, soft delete) do not change.

## 3. Out of scope

- Changing the posting engine, ledgers, periods, dimensions, GST or TDS rules. (They gain a tenant context; their logic is unchanged.)
- Consolidation across Legal Entities and intercompany elimination (already planned as separate capabilities).
- The platform's own billing, catalog and subscription screens (EIS).
- Moving a Legal Entity from one group to another (the platform's transfer feature). Finance only has to state whether it supports transfer (see REQ-GLT-081).
- Ledger sharing, calendar sharing and ledger status (DEBT-04, DEBT-05, DEBT-06) and the dimension-type redesign (DEBT-01). They are separate items, but the order of work in the plan avoids rebuilding them twice.

## 4. Actors

| Actor | Description |
|---|---|
| Platform (EIS) | The only writer of groups, organizations, units, teams, people, memberships and subscriptions. Sends signed messages; also asks Finance questions |
| Group Admin (platform level **Admin**) | Administers the whole group in Finance |
| Organization Admin | Administers one Legal Entity |
| Unit Admin | Administers one Business Unit |
| Team Lead, User | Ordinary people; each has a Finance functional role (GL Manager, Accountant, Approver, Viewer, Auditor) |
| Platform operator | Vyoog staff. Runs the platform. Has no rights inside a group |
| Another product (for example Macro) | Calls Finance as a service client with a grant for one or more groups |

## 5. Requirements

Priority: **M** = must, **S** = should. Phase refers to the [plan](../../../08-architecture/multi-tenancy/gap-analysis-and-adoption-plan.md). "Gap" refers to the findings F-nn in the [current-state analysis](../../../08-architecture/multi-tenancy/finance-current-state.md).

### 5.1 Tenant boundary

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-001 | A **group** shall be the tenant. Each group shall have its own database. | M | 1 | F-01 |
| REQ-GLT-002 | Finance shall have one schema per group, named from the group code by the platform. All Finance tables of a group (what are now `gl`, `aie` and `auth`) shall live in that one schema. | M | 1 | F-01 |
| REQ-GLT-003 | Finance shall serve requests with a runtime database role that can change data but cannot change structure, cannot read another product's schema, and bypasses no row-level security. It shall run migrations with a separate migration role. | M | 1 | F-01 |
| REQ-GLT-004 | Finance shall never take a host, database name, schema name, user name or password from a request or from a message. Host, port and SSL mode shall come from Finance's own configuration; names shall be recomputed from the group code; passwords shall be references. | M | 2 | F-09 |
| REQ-GLT-005 | Finance shall keep working for an existing single-database customer (the "legacy" tenant) until it is moved, without a code branch per customer. | M | 5 | F-01 |

### 5.2 Choosing the tenant

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-010 | The tenant of a request shall be decided from the verified identity of the caller and the registry, never from the address, host, a token claim naming a group, or a header the caller controls. | M | 1 | F-01 |
| REQ-GLT-011 | A person shall be matched to a membership by the identity provider's subject. A match by e-mail shall be allowed only when the token says the e-mail is verified. | M | 1 | F-06 |
| REQ-GLT-012 | A person in several groups shall choose with `X-TENANT-ID`, which may only choose among the person's own groups; any other value shall answer like an unknown group. With no header the person's default membership shall be used; with none, the request shall be refused with `GROUP_CHOICE_REQUIRED`. | M | 1 | |
| REQ-GLT-013 | A service (another product) shall be bound to the groups granted to its client; a service with no grant shall be refused. | M | 1 | |
| REQ-GLT-014 | Refusals shall use the platform's codes and shape `{status, code, message}` (see [business rules](business-rules.md) BR-GLT-010). | M | 1 | |
| REQ-GLT-015 | The registry shall be read fail closed: when it cannot be read and no earlier answer is held, requests shall be refused with `503 REGISTRY_UNAVAILABLE`. Answers may be cached for at most 60 seconds. | M | 1 | |
| REQ-GLT-016 | Only a tenant in state READY shall serve requests or run jobs. | M | 1 | |

### 5.3 Connection to the platform

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-020 | Finance shall receive platform messages at `POST /api/internal/platform/webhook`, signed with HMAC-SHA256 over `timestamp.body` using a secret from the environment, with the platform's four `X-EIS-*` headers, a 300-second tolerance, constant-time comparison, and `404` while no secret is configured. | M | 2 | F-11 |
| REQ-GLT-021 | Finance shall handle the message types `schema_provision_requested`, `schema_status`, `provision_requested`, `suspend`, `resume`, `deprovision_requested` and `group_snapshot`, and answer with the platform's state vocabulary (`DONE`, `READY`, `MIGRATING`, `SEEDING`, `SYNCING`, `RUNNING`, `FAILED`). | M | 2 | F-07, F-11 |
| REQ-GLT-022 | On `schema_provision_requested` Finance shall verify the names, register the placement as REQUESTED, then run its migrations as the migration role and verify the runtime role, and only then mark the tenant READY. A second instance shall not migrate the same group at the same time. | M | 2 | |
| REQ-GLT-023 | `suspend` shall make the tenant read-refused (403) without touching data; `resume` shall restore it; `deprovision_requested` shall be acknowledged and shall never delete data. | M | 2 | |
| REQ-GLT-024 | Finance shall not provision databases, schemas or roles itself. | M | 2 | |
| REQ-GLT-025 | Messages shall be idempotent: the same event id or the same snapshot version shall change nothing. | M | 2 | |

### 5.4 Hierarchy synchronization

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-030 | A `group_snapshot` shall be applied in one transaction and shall be the full truth for what the platform masters: a Legal Entity or Business Unit that is missing shall be deactivated, never deleted. | M | 2 | |
| REQ-GLT-031 | Each platform organization shall create or update exactly one Legal Entity, and the group shall be exactly one Business Group (see [hierarchy-mapping.md](../../../08-architecture/multi-tenancy/hierarchy-mapping.md)). Each unit of type Business Unit shall create or update one Business Unit under its Legal Entity. | M | 2 | |
| REQ-GLT-032 | Finance shall keep a mirror of the platform tree (nodes with their platform references) so reach can be decided. | M | 2 | |
| REQ-GLT-033 | In a platform-managed group, creating, renaming, moving or deleting a Business Group, Legal Entity or Business Unit in Finance shall be refused with `409 PLATFORM_MANAGED`. The Finance-owned accounting attributes (accounting standard, TAN, GSTIN, state code, es mode, currency) shall stay editable. | M | 2 | F-08 |
| REQ-GLT-034 | A Legal Entity or Business Unit created from a snapshot shall have its Finance attributes marked incomplete, and the gates in business rule BR-GLT-023 shall apply until they are complete. | M | 2 | |
| REQ-GLT-035 | A snapshot without the `levels` field (an older platform) shall be allowed to add organizations, units and people but shall not change levels, home units or grants. | S | 2 | |
| REQ-GLT-036 | A snapshot shall never create ledgers, calendars, accounts, dimensions, journals or any other business record. | M | 2 | |
| REQ-GLT-037 | The seeded default administrator of a new schema shall be deactivated by the first snapshot. | M | 2 | F-07 |

### 5.5 Identity and sign-in

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-040 | Finance shall validate the platform's identity provider tokens (Keycloak realm `eVyoog`) as an OAuth2 resource server and shall stop issuing its own tokens. | M | 3 | F-06 |
| REQ-GLT-041 | Finance shall expose `GET /api/auth/sso-config` and `GET /api/auth/session` as Agile and Macro do, redeeming the platform session backend to backend with the shared-secret header, and shall never return the cookie value to the browser. | M | 3 | F-06 |
| REQ-GLT-042 | With `PLATFORM_SSO_REQUIRED=true` the sign-in page shall show no password form; with no platform session it shall redirect once to the platform login, and a person with no membership shall see why instead of looping. | M | 3 | F-06 |
| REQ-GLT-043 | Sign-out shall go to the platform's sign-out page; other tabs shall sign out together; the product shall notice an ended platform session within about one minute and on a 401. | M | 3 | F-06 |
| REQ-GLT-044 | Finance shall store no password, no password hash and no refresh token of its own for people in a platform group. The change-password, reset-password and own-login endpoints shall be removed from platform groups. | M | 3 | F-06, F-16 |
| REQ-GLT-045 | Authentication shall never imply access: the tenant checks shall run on every request. | M | 3 | |
| REQ-GLT-046 | The browser shall not hold the product's own refresh token. | S | 3 | F-15 |

### 5.6 Roles and reach

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-050 | Finance shall use the five platform levels (Admin, Organization admin, Unit admin, Team Lead, User), stored as `ADMIN`, `ORG_ADMIN`, `UNIT_ADMIN`, `TEAM_LEAD`, `USER`, taken from the snapshot through a role map; an unknown role shall map to the least-privileged level. | M | 3 | |
| REQ-GLT-051 | Only the Admin level shall be a group administrator. Organization admin and Unit admin shall hold the Admin permissions except the group-wide ones and shall be limited by reach. | M | 3 | |
| REQ-GLT-052 | Finance's functional roles (GL Manager, GL Accountant, GL Approver, GL Viewer, GL Auditor) shall remain, assigned in Finance within the assigner's reach, and shall be combined with the level: the level decides where, the functional role decides what. | M | 3 | |
| REQ-GLT-053 | **The Legal Entity of every request shall be checked on the server.** A caller may read or change data of a Legal Entity only if it is within their reach. A Legal Entity id sent in a path, query or body that is outside reach shall answer `404` as if it did not exist. | M | **0 then 4** | F-02, F-14 |
| REQ-GLT-054 | List endpoints (business groups, legal entities, ledgers, users, roles, approval policies, audit log and every other list) shall return only what is within the caller's reach and never every row of the schema. | M | **0 then 4** | F-03, F-14 |
| REQ-GLT-055 | A person's reach shall be: the Legal Entities and Business Units under their home node, plus those under nodes they were given, with the Admin level reaching the whole group. A person with no home node and no grant shall read nothing. | M | 4 | |
| REQ-GLT-056 | If reach cannot be computed the request shall be refused with `503 SCOPE_UNAVAILABLE`. | M | 4 | |
| REQ-GLT-057 | A person with a Finance role in more than one Legal Entity shall be able to choose the active Legal Entity in the application, and the server shall check that choice against their reach on every request. | M | 4 | F-05, F-17 |
| REQ-GLT-058 | Reach shall also be enforced in the database by row-level security on the Legal-Entity-scoped tables, with the session setting carried per request, as defence in depth. | S | 4 | |
| REQ-GLT-059 | A person with the right to administer shall be able to give extra Legal Entities or Business Units to a person, and remove them. A grant that came from the platform shall be removed only by the platform. | S | 4 | |
| REQ-GLT-060 | A person shall be able to see their own reach (group, active Legal Entity, units, level, permissions, screens), and an administrator shall be able to see everyone's. | S | 4 | |
| REQ-GLT-061 | The last administrator of a group shall not be removable or demotable. | M | 3 | |

### 5.7 Entitlement and subscription

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-065 | A request shall be refused unless the group is active, the member is active, the subscription is active or in trial and has not ended, and the member has Finance access, with the platform's codes. The end of a subscription shall be checked on every request. | M | 2 | F-11 |
| REQ-GLT-066 | An entitlement switch (`entitlement.enforce`) shall exist as a rollback, off only for the end-date and product-access checks. | S | 2 | |
| REQ-GLT-067 | For sensitive actions (setup of the group structure, user and role changes, audit log, period permanent close, reopen) Finance may ask the platform to confirm and shall fail closed (`503 ENTITLEMENT_UNAVAILABLE`) when it cannot. | S | 4 | |
| REQ-GLT-068 | A term that ended with grace (access mode READ_ONLY) shall refuse writes and allow reads. | S | 2 | |
| REQ-GLT-069 | Capabilities (`gl.capability_registry`, the unit of activation and billing) shall be activated per group from the subscription's plan, and not by hand. | S | 2 | |

### 5.8 Audit and identity of the actor

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-070 | The actor of every write and every audit row shall be the verified identity from the token. The `X-User-Id` header shall be ignored for this purpose. | M | **0** | F-04 |
| REQ-GLT-071 | Audit rows shall carry the group and, where it applies, the Legal Entity. Tenancy, hierarchy, role and grant changes shall be audited. | M | 2 | |
| REQ-GLT-072 | A refusal (denied by scope or tenant) shall be audited, not only logged. | S | 4 | |

### 5.9 Scheduled work

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-075 | Scheduled jobs (recurring journal generation, period jobs, import retries) shall run once per tenant with a lease per job and tenant, failure of one tenant shall not stop the others, and a run longer than 15 minutes shall lose its lease. | M | 5 | |
| REQ-GLT-076 | Jobs shall skip a group whose subscription ended or whose tenant is not READY. | M | 5 | |

### 5.10 Operations

| ID | Requirement | P | Phase | Gap |
|---|---|---|---|---|
| REQ-GLT-080 | Finance shall publish counters for refusals by code, snapshot results and open tenant pools, and log the group on every line. | S | 5 | |
| REQ-GLT-081 | Finance shall state in the product connection whether it supports transfer of a Legal Entity between groups (initially: not supported). | S | 5 | |
| REQ-GLT-082 | An isolation test suite shall run two tenants with their own non-superuser roles over real HTTP and shall cover: spoofed headers, other tenants' ids, injection text, a person in two groups, a service client without a grant, and parallel requests. | M | 5 | |
| REQ-GLT-083 | An existing customer in the shared database shall be moved into its own group database by a written runbook with a restore rehearsal, and the cut-over shall be reversible until the old data is retired. | M | 5 | F-01 |
| REQ-GLT-084 | Per-environment addresses and switches shall come from configuration: `EIS_BACKEND_URL`, `EIS_LOGIN_URL`, `EIS_LOGOUT_URL`, `PLATFORM_SSO_ENABLED`, `PLATFORM_SSO_REQUIRED`, `INTERNAL_SSO_SHARED_SECRET`, `PROVISIONING_WEBHOOK_SECRET`, `DATABASE_PROVIDER`, `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_SSLMODE`, `EIS_PRODUCT_SCHEMA_CODE`. Secrets shall be environment values or secret references, never in files or in the repository. | M | 2 | F-09, F-18 |

### 5.11 Hardening that does not depend on the platform (Phase 0)

| ID | Requirement | P | Gap |
|---|---|---|---|
| REQ-GLT-090 | The setup wizard shall require authentication and the group-level permission. | M | F-08 |
| REQ-GLT-091 | Login shall refuse a Legal Entity the person has no role in. | M | F-05 |
| REQ-GLT-092 | The database password in `CLAUDE.md` and in any task definition shall be rotated and replaced by a secret reference. | M | F-09 |
| REQ-GLT-093 | The stray files `FROM`, `JOIN`, `SELECT`, `WHERE`, `http`, `import` and `main` at the repository root shall be removed. | S | |
| REQ-GLT-094 | The hard-coded Business Group id in `EnterpriseStructurePage` and `CoaStructurePage` shall be replaced by the id from the signed-in context. | M | F-13 |

## 6. Constraints taken from Finance's own rules

These existing rules are kept and every requirement above must respect them: write isolation (no writing outside Finance's own schema), audit every write in the same transaction, soft delete only, UUID keys, DTOs only in responses, the structured error shape, never edit an applied migration, and `ddl-auto: validate`. The statement in `CLAUDE.md` that Finance is "not microservices, one database" and that it writes only to `gl.*` and `aie.*` changes with this feature and needs the Domain SME's decision (open decision D-10 in the plan).

## 7. Dependencies

- The platform's webhook, snapshot and SSO endpoints, and the shared secrets, being available to Finance's environments.
- Keycloak permissions for the token exchange (not yet verified by any product).
- A registry location (a control schema or database) and its migration tree.
- The platform's product catalog entry for Finance: product code, schema code, webhook URL, SSO backend URL, launch URL.

## 8. Traceability

Requirement → [business rules](business-rules.md) → [workflows](../../../04-workflows/multi-tenancy-and-org-hierarchy/README.md) → [UI](ui-requirements.md) → [API](api-requirements.md) → database (plan, phase 1 and 2) → frontend and backend (not started) → [acceptance criteria](acceptance-criteria.md) and test cases (to be written under `test-cases/`) → UAT → release.
