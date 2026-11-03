# Finance (GL) — current state: tenancy, organization, sign-in, roles

Status: **analysis of the code as of commit `22f7589` (3 Nov 2026).** Written from the repository, not from memory.
Read with: [hierarchy-mapping.md](hierarchy-mapping.md) and [gap-analysis-and-adoption-plan.md](gap-analysis-and-adoption-plan.md).
Requirement: [FRD multi-tenancy and organization hierarchy](../../02-requirements/FRD/multi-tenancy-and-org-hierarchy/requirement.md).

## 1. What the service is today

| Item | As built | Where |
|---|---|---|
| Application | One Spring Boot application, Java 21, packages under `com.evyoog.gl` (enterprise, ledger, dimension, coa, calendar, periodmanagement, journal, posting, aie, balance, reporting, localisation, audit, auth, wizard) | `src/main/java/com/evyoog/gl` |
| Pattern | "Capability-based modular, not microservices": one application, one database | `CLAUDE.md` |
| Database | One shared PostgreSQL database `vygmicroservice` on one RDS instance; fixed schemas `gl` (business tables), `aie` (import pipeline) and `auth` (users and roles) | `CLAUDE.md`, `application.yml` (`default_schema: gl`, Flyway `schemas: gl,aie,auth`) |
| Migrations | Flyway `V1`..`V35` in `src/main/resources/db/migration`, one file per capability | `db/migration` |
| API | About 150 REST endpoints under `/api/v1/...`, responses in an `ApiResponse<T>` envelope | `*Controller.java` |
| Tenant concept | **None.** No tenant, group or customer key exists in code, tables or configuration | search of `src/main` |

So "tenant" in Finance does not exist yet. The first level of its own hierarchy, the Business Group, is a business concept (a governance root with a Thin or Thick mode), not an isolation boundary.

## 2. Finance's own organization hierarchy

```
Consumption Context        gl.consumption_context   the customer record: code, name, segment type, organisation name, contact
 └─ Business Group         gl.business_group        governance root; es_mode THICK_ES | THIN_ES; default currency
     └─ Legal Entity       gl.legal_entity          accounting standard, TAN; unique (business_group_id, code)
         └─ Business Unit  gl.business_unit         1 BU = 1 state = 1 GSTIN; unique (legal_entity_id, code)
             └─ Inventory Organisation  gl.inventory_organisation   optional under Thin ES
                 └─ Sub-inventory       gl.sub_inventory
```

Rules already in `CLAUDE.md` and the code:
- A Thin ES Business Group may have exactly one Legal Entity (`THIN_ES_LE_LIMIT`).
- Posting, period status, approval policy and reporting are scoped to the Legal Entity. A ledger can be assigned to a Legal Entity through `gl.legal_entity_ledger`.
- GSTIN belongs to the Business Unit, TAN to the Legal Entity.
- All tables carry audit columns; all writes also write `gl.audit_log` in the same transaction; deletes are soft (`is_active = false`).

The Consumption Context is the closest thing Finance has to a customer record. It is the natural place to attach the platform's group reference (see the mapping document).

## 3. Sign-in, identity and roles

| Item | As built | Where |
|---|---|---|
| Identity provider | **Finance's own.** Users live in `auth.users` with a bcrypt `password_hash`; sign-in is `POST /api/v1/auth/login` and `/refresh`; Finance signs and checks its own JWT | `auth/service/AuthService`, `JwtService` |
| Link to Keycloak or the platform | None. No Keycloak, no single sign-in, no platform session | search of `src/main` |
| Roles | `auth.roles` seeded with `SYS_ADMIN`, `GL_MANAGER`, `GL_ACCOUNTANT`, `GL_APPROVER`, `GL_VIEWER`, `GL_AUDITOR`; permissions are strings such as `gl:journal:approve` held in `auth.permissions` and `auth.role_permissions` | `V23__auth01_security.sql` |
| Role scope | `auth.user_roles (user_id, role_id, legal_entity_id)`: a role is granted **per Legal Entity** | `V23` |
| Login context | Login picks one Legal Entity (the one asked for, else the user's first). Permissions are resolved for that user and entity only, and put in the JWT with a `legalEntityId` claim | `AuthService.resolveLegalEntityId`, `resolvePermissions`, `JwtService` |
| Authorisation | `@PreAuthorize("hasAuthority('gl:...')")` on endpoints; `JwtAuthenticationFilter` turns the `permissions` claim into authorities | controllers, `JwtAuthenticationFilter` |
| Approval | `auth.approval_policy` chooses approvers by Legal Entity, Business Unit and source | `V23` |
| Open endpoints | `/api/v1/auth/login`, `/refresh`, `/actuator/health`, API docs, and **`/api/v1/gl/setup-wizard/**`** | `SecurityConfig` |
| CORS | Fixed list in code: `https://finance.evyoog.com`, `http://localhost:5173`, `*.app.github.dev` | `SecurityConfig` |

## 4. Findings (what a multi-tenant platform product must not do)

These are observations about the code. Each is tied to a requirement or a gap item in the gap analysis.

| # | Finding | Evidence | Why it matters |
|---|---|---|---|
| F-01 | No tenant boundary: every customer's data would share the `gl`, `aie` and `auth` tables of one database | one `vygmicroservice` database; no tenant key on any table | The platform model is one database per group. A second customer cannot be hosted safely today |
| F-02 | **The caller's Legal Entity is not enforced on data endpoints.** The JWT carries `legalEntityId`, but only the login code reads it back. Endpoints take `legalEntityId` as a request parameter and use it as given. Only `PeriodManagementService` checks the caller's role for the entity | `SecurityContextHolder` is used only in `JwtAuthenticationFilter`; 74 controller lines take `legalEntityId`; `UserRoleRepository` is used only by auth, user and period-management services | A person with `gl:journal:view` for entity A can ask for entity B's journals by passing B's id. Permission is checked, ownership is not |
| F-03 | List endpoints return every row | `BusinessGroupService.list` and `LegalEntityService.list` call `repository.findAll()` when no filter is passed | With more than one customer in the database, one customer can see another's setup |
| F-04 | Audit identity is client supplied: `X-User-Id` request header, default `system` | 71 uses of `@RequestHeader("X-User-Id")` in controllers | Audit rows can be written under any name; the audit trail is not trustworthy for a regulated finance record |
| F-05 | Login accepts any `legalEntityId` in the request, and only discovers that the person has no role there by getting an empty permission set | `resolveLegalEntityId` returns the requested id without checking assignment | Not a privilege gain (permissions stay empty), but it issues a token for an entity the person is not part of |
| F-06 | Own password store and own JWT | `auth.users.password_hash`, `JwtService` | Cannot take part in one sign-in and one sign-out; no MFA or federation rules of the platform apply |
| F-07 | Cold start needs hand-written SQL: the seeded `SYS_ADMIN` has no Legal Entity, and every endpoint (including enterprise setup) needs a permission | `CLAUDE.md`, AUTH-01 notes | Provisioning a customer needs a person with database access. The platform model provisions by signed message instead |
| F-08 | The setup wizard is open to everyone (`permitAll`) | `SecurityConfig` | Anyone who can reach the service can create a Business Group and a Legal Entity |
| F-09 | A database password default is written in `CLAUDE.md` and the same value is in the Agile task definition | `CLAUDE.md` "Connection" block | Treat it as exposed: rotate, and move to a secret reference |
| F-10 | The user list, roles and approval policies are global, not per group | `auth.users` unique on email across the database | Two customers could not use the same e-mail address; one customer's admin could affect another's users |
| F-11 | No platform link: no registry, no subscription or entitlement gate, no signed webhook, no snapshot | search of `src/main` | Cannot be sold, switched on or suspended per group |
| F-12 | `CLAUDE.md` states two things this work changes: "NOT microservices / one database" and "write only to `gl.*`, `aie.*`" (AUTH-01 already added `auth.*`) | `CLAUDE.md` | These statements need the Domain SME's decision before the model in this FRD is built |

### 4a. Findings from the Finance frontend (`evyoog/evyoog-frontend`, commit `94c4999`)

React 19, Vite, TypeScript, Tailwind v4, axios, react-router-dom. 24 pages. Its own `CLAUDE.md` records several workarounds that confirm the findings above.

| # | Finding | Evidence | Why it matters |
|---|---|---|---|
| F-13 | The Business Group id is **hard-coded** in two pages "for Phase 1 single-tenant" | `EnterpriseStructurePage.tsx` and `CoaStructurePage.tsx` use the constant `BUSINESS_GROUP_ID` | In a multi-tenant product the group comes from the signed-in context, never from a constant |
| F-14 | The frontend already hit the missing Legal Entity scoping: "`listLedgers(legalEntityId)[0]` returned the wrong ledger for Unicon users (Orbinox's PRIM-01 came first)"; it now resolves the primary ledger through the Legal Entity link instead. Other screens call `listLedgers()` with no filter as a "system-wide picker" and may "still show other tenants' ledgers if the backend ignores the param" | frontend `CLAUDE.md`, "P&L By-Segment: Dynamic Dimensions" | This is F-02 and F-03 seen from the user's side, with two customers (Orbinox and Unicon) in one database |
| F-15 | Tokens and the user record are kept in `localStorage` (`accessToken`, `refreshToken`, `user`); the app refreshes the JWT itself and shows a 5-minute session timeout warning | `src/api/axios.ts`, `SessionTimeoutWarning.tsx` | With one sign-in and one sign-out the browser must not hold the product's own refresh token; sign-out has to end the platform session |
| F-16 | The product has its own sign-in page, change-password page, user management page and role management page | `LoginPage.tsx`, `ChangePasswordPage.tsx`, `UserManagementPage.tsx`, `RoleManagementPage.tsx` | In a platform group, people and their level come from the platform; these screens become read-only for people and keep only the Finance-owned parts (functional roles, approval policy) |
| F-17 | The Legal Entity shown in the top bar and used on every call comes from the login (`user.legalEntityId`), changed only by signing in again | `AuthContext`, `TopBar.tsx` | A person with roles in several Legal Entities needs a visible way to choose, and the choice must be checked by the server |
| F-18 | The backend base URL is a build-time value (`VITE_API_BASE_URL`) and the Codespaces CORS list is in the backend code | `src/api/axios.ts`, `SecurityConfig` | Live addresses must come from configuration per environment |

Also noted (not tenancy, but found while reading):
- The repository root contains stray files named `FROM`, `JOIN`, `SELECT`, `WHERE`, `http`, `import` and `main`. They look like accidental commits from shell redirects.
- `CLAUDE.md` says Spring Boot 3.3 in one place and 4.0.7 in another; the later note is the correct one.

## 5. What is already good and can be kept

- Per-Legal-Entity period status, approval and reporting are already the right grain for the platform's "unit" level.
- Permissions are fine-grained strings (`gl:module:action`) and roles are data, close to the platform's matrix model.
- The four implementation rules (write isolation, audit every write in the same transaction, soft delete, UUID keys) fit a per-group schema without change.
- Flyway one file per capability, `ddl-auto: validate`, and a structured error shape.
- Role checks for approval and for reopening a closed period already look at the caller's real role, not only the permission.
