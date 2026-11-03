# Business rules: multi-tenancy and organization hierarchy (Finance)

Status: **Draft.** Rules BR-GLT-001 to BR-GLT-066. Each rule states where it comes from: **P** = taken from the platform and the other products (see the [reference model](../../../08-architecture/multi-tenancy/platform-reference-model.md)), **F** = a Finance rule that is kept, **N** = new for Finance, decided in the [hierarchy mapping](../../../08-architecture/multi-tenancy/hierarchy-mapping.md).
Requirements are in [requirement.md](requirement.md).

## A. Tenant and tenant choice

| ID | Rule | Src |
|---|---|---|
| BR-GLT-001 | A **group** is the tenant. Everything Finance stores for a customer belongs to exactly one group. | P |
| BR-GLT-002 | A group has its own database. Finance has one schema in it, named by the platform from the group code (`<schemaCode>_<group>`); all Finance tables of the group are in that one schema. | P, N |
| BR-GLT-003 | The tenant is decided from the verified identity and the registry. It is never decided from the address, the host, the `Origin`, a token claim that names a group, or a header that names a group the caller does not belong to. | P |
| BR-GLT-004 | `X-TENANT-ID` may only **choose among the caller's own groups**. A group that is not theirs answers exactly as a group that does not exist. | P |
| BR-GLT-005 | A person is matched to a membership by the identity provider's subject. A match by e-mail is allowed only if the token says the e-mail is verified; an unverified e-mail never links, enrols or routes. | P (Macro) |
| BR-GLT-006 | A service (another product) acts only for the groups granted to its client (`azp`). No grant: refused. One grant: that group. Several: `X-TENANT-ID` among the granted groups. | P |
| BR-GLT-007 | **Fail closed.** If the registry, the entitlement or the scope cannot be read, the request is refused. Finance never falls back to a default tenant for a signed-in call. | P |
| BR-GLT-008 | Only a tenant in state READY serves requests and runs jobs. The other states refuse with a code (see BR-GLT-010). | P |
| BR-GLT-009 | A message or a request never names a host, database, schema, user or password. Finance reads host, port and SSL mode from its own configuration, recomputes the names from the group code and compares them, and takes passwords only as references (`local:`, `aws:`, `env:`, `file:`). A message that disagrees is refused. | P |

## B. Refusals and gate order

| ID | Rule | Src |
|---|---|---|
| BR-GLT-010 | Refusals use `{status, code, message}` and these codes. | P |
| BR-GLT-011 | The gate runs in this order, and the first failure answers: (0) registry readable, (1) a membership or grant exists, (2) group active, (3) member active, (4) subscription present and active or in trial, (5) subscription not ended, (6) the member has Finance access, (7) tenant provisioned and READY, then scope (BR-GLT-037 to 041). | P |

| Code | Meaning | HTTP |
|---|---|---|
| `REGISTRY_UNAVAILABLE` | registry cannot be read and nothing is cached | 503 |
| `NOT_A_MEMBER` | no membership, or not in the named group | 403 |
| `NO_GRANT` | a service client with no grant | 403 |
| `GROUP_CHOICE_REQUIRED` | several groups, none chosen, no default | 403 |
| `ORG_INACTIVE` | group or organization not active | 403 |
| `USER_INACTIVE` | membership disabled | 403 |
| `NO_SUBSCRIPTION` / `SUBSCRIPTION_NOT_ACTIVE` | no subscription, or suspended or cancelled | 403 |
| `SUBSCRIPTION_ENDED` | end date reached (checked every request) | 403 |
| `NO_PRODUCT_ACCESS` | the member has no access to Finance | 403 |
| `NOT_PROVISIONED` / `TENANT_NOT_READY` | schema not ready | 503 |
| `TENANT_UNAVAILABLE` | suspended, failed or being retired (carries the state) | 403 |
| `SCOPE_UNAVAILABLE` | reach cannot be computed | 503 |
| `ENTITLEMENT_UNAVAILABLE` | the platform's answer is needed and unavailable | 503 |
| `PLATFORM_MANAGED` | a change the platform owns | 409 |
| `PLATFORM_UNAVAILABLE` | a write-through to the platform failed | 503 |
| `ACCESS_DENIED` | permission missing (existing Finance code) | 403 |

## C. Connection and provisioning

| ID | Rule | Src |
|---|---|---|
| BR-GLT-012 | The webhook is `POST /api/internal/platform/webhook`, with no bearer token. It carries `X-EIS-Event-Id`, `X-EIS-Event-Type`, `X-EIS-Timestamp` and `X-EIS-Signature: sha256=<hex>`, the HMAC-SHA256 of `timestamp + "." + body` with `PROVISIONING_WEBHOOK_SECRET`. The comparison is constant-time, a timestamp more than 300 seconds off is refused (401), and while no secret is set the endpoint answers 404. | P |
| BR-GLT-013 | Messages are idempotent: the same event id, or the same snapshot version, changes nothing. A snapshot is applied only if its version differs from the last accepted one. | P |
| BR-GLT-014 | Provisioning steps in Finance: **validate** (the schema exists and is owned by the migration role, else `SCHEMA_MISSING` or `SCHEMA_NOT_OURS`), **migrate** (as the migration role, under a lock per group), **verify** (as the runtime role: tables readable; no superuser, create-database, create-role, replication or row-security bypass; cannot create objects in its own schema or in `public`; cannot use another product's schema), **mark READY**. A failure marks only that group FAILED with the failed step; a READY group keeps serving during an upgrade. | P |
| BR-GLT-015 | `suspend` and `resume` switch the tenant between READY and SUSPENDED and never touch data. `deprovision_requested` is acknowledged and never deletes anything. Suspension, expiry and cancellation never delete a schema or data. | P |
| BR-GLT-016 | Finance never creates a database, schema or database role. The platform does. | P |

## D. Hierarchy

| ID | Rule | Src |
|---|---|---|
| BR-GLT-017 | The platform is the **single writer** of groups, organizations, units, teams, people, memberships and subscriptions. In a platform-managed group Finance only applies what the snapshot says. | P |
| BR-GLT-018 | A snapshot is the full truth for what the platform masters. What is missing from a later snapshot is **deactivated, never deleted**. Finance's existing soft delete (`is_active = false`) is used. | P, F |
| BR-GLT-019 | A snapshot never creates business records (ledgers, calendars, accounts, dimensions, journals, balances, approval policies). It creates only structure, people and memberships. A snapshot with no `levels` (an older platform) may add organizations, units and people but changes no level, home unit or grant. | P |
| BR-GLT-020 | **Group = Business Group**, one to one. The group code is permanent. | N |
| BR-GLT-021 | **Organization = Legal Entity**, one to one, matched by the platform reference (`org:<id>`). | N |
| BR-GLT-022 | **A unit of type Business Unit = Business Unit**, matched by `node:<id>`, always directly under its Legal Entity whatever the platform nesting; the full path is kept in the mirror. Units of other types are not Business Units. Teams are not mapped. | N |
| BR-GLT-023 | A Legal Entity or Business Unit created by a snapshot is **incomplete** until its Finance attributes are given. Legal Entity (accounting standard, ledger assignment): an incomplete Legal Entity cannot post journals. Business Unit (state code, GSTIN): an incomplete Business Unit cannot carry GST transactions. Nothing else is blocked. | N |
| BR-GLT-024 | Finance-owned attributes stay editable in a platform-managed group: es mode and default currency (Business Group); accounting standard and TAN (Legal Entity); GSTIN and state code (Business Unit); everything below the Business Unit (Inventory Organisation, Sub-inventory). Creating, renaming, moving or deleting a Business Group, Legal Entity or Business Unit is refused with `409 PLATFORM_MANAGED`. | N |
| BR-GLT-025 | A Thin ES Business Group has exactly one Legal Entity. When a snapshot brings a second organization, the snapshot is applied (the platform is the single writer), the Business Group is marked "needs Thick ES", and posting on the extra Legal Entity is blocked until a Finance administrator switches the mode. | N |
| BR-GLT-026 | GSTIN may be shared by Business Units of one Legal Entity (since V32) and is checked against other Legal Entities. This Finance rule is unchanged. | F |
| BR-GLT-027 | The hierarchy is structural. It grants no permission and changes no subscription by itself. | P |
| BR-GLT-028 | The seeded default administrator of a new schema is deactivated by the first snapshot. | P |

## E. Roles and reach

| ID | Rule | Src |
|---|---|---|
| BR-GLT-030 | There are five levels, the same in every product: Admin (`ADMIN`, platform `GROUP_ADMIN`), Organization admin (`ORG_ADMIN`), Unit admin (`UNIT_ADMIN`), Team Lead (`TEAM_LEAD`), User (`USER`, platform `MEMBER`). The platform role is mapped by a role map; an unknown role maps to `USER`; a role of another product never grants a level. | P |
| BR-GLT-031 | **Only Admin is a group administrator.** Organization admin and Unit admin hold the Admin permissions except the group-wide ones and are limited by reach. | P |
| BR-GLT-032 | The level decides **where** a person acts; the Finance functional role (GL Manager, Accountant, Approver, Viewer, Auditor) decides **what** they may do there. An action needs both. The existing permission strings are unchanged. | N |
| BR-GLT-033 | In a platform-managed group a person's level and home unit are set by the platform and cannot be changed in Finance (`409 PLATFORM_MANAGED`). | P |
| BR-GLT-034 | A functional role given in Finance is applied when the person is new or when the platform changes their level; it is not overwritten by each snapshot. It can be given only within the giver's reach, by an administrator of that Legal Entity or above, and only a group administrator can give the Admin level. | P (Agile, Macro) |
| BR-GLT-035 | The last active administrator of a group cannot be demoted, disabled or removed. A person cannot disable themselves. | P (Agile) |
| BR-GLT-036 | The server decides the level, the Legal Entity and the unit from live data. A role, Legal Entity or unit id sent by the browser is a request, not a fact. | P |
| BR-GLT-037 | **Reach**: the Legal Entities and Business Units under the person's home node, plus those under nodes they were given. The Admin level reaches the whole group. | P, N |
| BR-GLT-038 | A person with no home node and no grant reads nothing. | P |
| BR-GLT-039 | Data outside reach answers `404`, the same as data that does not exist. Data inside reach where the person lacks the right answers `403`. | P |
| BR-GLT-040 | **The Legal Entity of every request is checked on the server**: the id in the path, the query, the body, and every list. A list returns only what is within reach. No endpoint trusts a Legal Entity id because the token or the request names it. | N |
| BR-GLT-041 | A person with a role in several Legal Entities has one **active Legal Entity**, chosen in the application. The choice is carried on each request and checked against reach. A token never fixes the Legal Entity for its whole life. | N |
| BR-GLT-042 | Extra Legal Entities or Business Units may be given by an administrator within their own reach; a grant that came from the platform is removed only by the platform. | P |
| BR-GLT-043 | Reach is enforced in the application and, as defence in depth, in the database by row-level security on the Legal-Entity-scoped tables. Neither replaces the tenant boundary, which is the separate database and role. | P |
| BR-GLT-044 | Inside one Legal Entity a person's reach is the whole ledger (one book). Business Unit reach on journals and reports is a later option (decision D-06). | N |
| BR-GLT-045 | The platform operator (`SYS_ADMIN`, platform administrator) has no rights inside a group. | P |

## F. Sign-in

| ID | Rule | Src |
|---|---|---|
| BR-GLT-046 | The platform is the one front door. In a platform group Finance holds no password, hash or refresh token of its own, and the own-login, change-password and reset-password endpoints are off. | P |
| BR-GLT-047 | Finance redeems the platform session backend to backend (`POST {EIS_BACKEND_URL}/internal/sso/token`, header `X-Internal-Sso-Secret`, body `{ssoSessionId, targetClientId}`) and never returns the cookie value to the browser. | P |
| BR-GLT-048 | Sign-out goes to `{logoutUrl}?redirect=<app>/home`. The platform ends the session, other tabs sign out together, and Finance notices an ended session within about one minute or on a 401. The platform returns a person only to an address whose origin equals the origin of a catalog `launchUrl`. | P |
| BR-GLT-049 | Authentication never implies access. The checks of section A and B run on every request. | P |

## G. Entitlement

| ID | Rule | Src |
|---|---|---|
| BR-GLT-050 | Entitlement is derived, not stored: an active subscription in term, the plan's included capabilities, and the member's Finance access. | P |
| BR-GLT-051 | A subscription is effective from its start to its end at `23:59:00.000 +05:30` on the end date, and is denied at once after. The end date is checked on every request. | P |
| BR-GLT-052 | A term that ended with grace gives access mode READ_ONLY: reads allowed, writes refused. | P |
| BR-GLT-053 | For sensitive actions Finance may ask the platform and fails closed. | P |
| BR-GLT-054 | A Finance capability (`gl.capability_registry`) is active for a group when its plan includes it. | N |

## H. Audit and jobs

| ID | Rule | Src |
|---|---|---|
| BR-GLT-055 | The actor of a write and of an audit row is the verified identity from the token. A client-supplied header is never the actor. | N |
| BR-GLT-056 | Audit rows carry the group and, where it applies, the Legal Entity. Hierarchy, role, grant and tenancy changes are audited; so are refusals by scope. The audit log stays immutable, and every write is audited in the same transaction. | F, N |
| BR-GLT-057 | Scheduled jobs run once per tenant. A lease per job and tenant is taken by one atomic statement on the database clock; a run longer than 15 minutes loses it; a failure in one tenant is logged with the group and does not stop the others; a group with an ended subscription or a tenant not READY is skipped. | P |

## I. Finance rules that stay

| ID | Rule | Src |
|---|---|---|
| BR-GLT-060 | Write isolation: Finance writes only to its own schema. (The wording in `CLAUDE.md`, "only `gl.*` and `aie.*`", is replaced by "only its own schema in the group's database".) | F |
| BR-GLT-061 | Audit every write in the same transaction; soft delete only; UUID keys; DTOs in responses; the structured error shape. | F |
| BR-GLT-062 | The period gate lives only in the posting engine; `finance_mode_snapshot` is set once. | F |
| BR-GLT-063 | Period status, approval, posting and reporting stay scoped to the Legal Entity. | F |
| BR-GLT-064 | Never edit an applied migration; Finance migrations use `"${flyway:defaultSchema}"`, never a literal schema, because the same scripts migrate every group's schema. | F, P |
| BR-GLT-065 | Tests never touch shared databases. | P |
| BR-GLT-066 | Before any query in a service method, the entity must be in a valid state (a query makes Hibernate flush a half-changed entity). This has caused two real bugs already. | F, P |

*Checked in the code:* the posting rule `validateLegalEntityAuthorised` (`PostingEngine.java`, line 418) only checks that the Legal Entity is linked to the ledger (`legal_entity_ledger`, active). It does not check who the caller is. So posting is also open to a caller who names a Legal Entity that is not theirs; BR-GLT-040 closes this by checking reach before the posting engine is reached, and the posting rule stays as it is.
