# Acceptance criteria: multi-tenancy and organization hierarchy (Finance)

Status: **Draft.** Given / when / then, grouped by requirement. A criterion is only "met" when it has been run; the status column stays "Not run" until then. Test-case documents for these criteria belong in `test-cases/`, one per criterion, using the id `AC-GLT-nnn`.
Never claim a criterion passed without running it. Finance's integration tests use Testcontainers; the platform pieces (Keycloak, the real webhook) are not available to tests, so criteria that need them are marked **Manual or staging**.

## A. Tenant boundary

| ID | Given | When | Then | Req | Status |
|---|---|---|---|---|---|
| AC-GLT-001 | Two groups A and B, each with its own database and schema, a person in each | The person of A asks for the list of Legal Entities | Only A's Legal Entities are returned; none of B's | REQ-GLT-001, 054 | Not run |
| AC-GLT-002 | The same two groups | The person of A calls a B resource by its id | `404`, the same body as an id that does not exist | REQ-GLT-053 | Not run |
| AC-GLT-003 | A tenant schema | The runtime role tries to create a table, to read another product's schema, or to create in `public` | Each is refused by the database | REQ-GLT-003 | Not run |
| AC-GLT-004 | A request with `X-TENANT-ID` naming B | Sent by a person who belongs only to A | Answers like an unknown group (not `403` with a hint that B exists) | REQ-GLT-012 | Not run |
| AC-GLT-005 | A webhook message that names a host, a database or a schema different from the one computed from the group code | Received | Refused (`state: FAILED`); nothing is connected | REQ-GLT-004 | Not run |
| AC-GLT-006 | Two tenants and 160 parallel requests, half for each | Run | No response contains the other tenant's data; no error | REQ-GLT-082 | Not run |

## B. Choosing the tenant and the gate

| ID | Given | When | Then | Req | Status |
|---|---|---|---|---|---|
| AC-GLT-010 | A person with an active membership and an active subscription | Calls any `/api/v1` endpoint | The call is served from the group's schema | REQ-GLT-010 | Not run |
| AC-GLT-011 | A token whose `tenant` claim names another group | Used | The claim is ignored; the tenant is the membership's | REQ-GLT-010 | Not run |
| AC-GLT-012 | A person matched only by an unverified e-mail | Calls Finance | `403 NOT_A_MEMBER`; no membership is created | REQ-GLT-011 | Not run |
| AC-GLT-013 | A person in two groups, no header, no default | Calls Finance | `403 GROUP_CHOICE_REQUIRED` | REQ-GLT-012 | Not run |
| AC-GLT-014 | A service client with no grant | Calls Finance | `403 NO_GRANT` | REQ-GLT-013 | Not run |
| AC-GLT-015 | The registry cannot be read and nothing is cached | Any signed-in call | `503 REGISTRY_UNAVAILABLE`; no fallback to another tenant | REQ-GLT-015 | Not run |
| AC-GLT-016 | A tenant in state MIGRATING, FAILED or SUSPENDED | A call, and a scheduled job | `503`, `503` naming the failed step, or `403`; the job is skipped | REQ-GLT-016 | Not run |
| AC-GLT-017 | A subscription whose end date has passed | The next call | `403 SUBSCRIPTION_ENDED` with no restart | REQ-GLT-065 | Not run |
| AC-GLT-018 | A member with no Finance access | Calls | `403 NO_PRODUCT_ACCESS` | REQ-GLT-065 | Not run |

## C. Connection and provisioning

| ID | Given | When | Then | Req | Status |
|---|---|---|---|---|---|
| AC-GLT-020 | No webhook secret configured | The webhook is called | `404` | REQ-GLT-020 | Not run |
| AC-GLT-021 | A message with a wrong signature, and one with a timestamp 10 minutes old | Received | `401` for both | REQ-GLT-020 | Not run |
| AC-GLT-022 | A valid `schema_provision_requested` for a new group | Received, then polled with `schema_status` | `RUNNING`, then `READY`; the schema was migrated as the migration role; the runtime role passes the checks | REQ-GLT-022 | Manual or staging |
| AC-GLT-023 | The same snapshot sent twice | Received twice | The second answers unchanged; nothing is rewritten | REQ-GLT-025 | Not run |
| AC-GLT-024 | `suspend` then `resume` | Received | Calls are refused `403` while suspended and served after; no row changed | REQ-GLT-023 | Not run |
| AC-GLT-025 | `deprovision_requested` | Received | Acknowledged; no data deleted | REQ-GLT-023 | Not run |
| AC-GLT-026 | A group code that collides with an existing hand-made tenant | Provisioned | Refused | REQ-GLT-004 | Not run |

## D. Hierarchy

| ID | Given | When | Then | Req | Status |
|---|---|---|---|---|---|
| AC-GLT-030 | A snapshot with one group, two organizations and three Business-Unit nodes | Applied | One Business Group, two Legal Entities, three Business Units under the right Legal Entities, each with its platform reference | REQ-GLT-031 | Not run |
| AC-GLT-031 | A Business-Unit node nested under a Division node | Applied | The Business Unit is directly under its Legal Entity; the path is in the mirror | BR-GLT-022 | Not run |
| AC-GLT-032 | A later snapshot without one Legal Entity and one Business Unit | Applied | Both are deactivated, not deleted; their posted journals still show | REQ-GLT-030 | Not run |
| AC-GLT-033 | A platform-managed group | `POST /api/v1/gl/legal-entities` | `409 PLATFORM_MANAGED` | REQ-GLT-033 | Not run |
| AC-GLT-034 | The same group | `PATCH` a Legal Entity's `tan`, and a Business Unit's `gstin` | Both accepted | REQ-GLT-033 | Not run |
| AC-GLT-035 | A Legal Entity created by a snapshot, Finance attributes missing | A journal is posted | Refused as incomplete; after the attributes are given it posts | REQ-GLT-034 | Not run |
| AC-GLT-036 | A Thin ES Business Group and a snapshot with two organizations | Applied | Applied; the group is marked "needs Thick ES"; posting on the second entity is blocked until switched | BR-GLT-025 | Not run |
| AC-GLT-037 | A snapshot | Applied | No ledger, calendar, account, dimension or journal is created | REQ-GLT-036 | Not run |
| AC-GLT-038 | A snapshot with no `levels` | Applied | Units and people may be added; no level, home unit or grant of an existing person changes | REQ-GLT-035 | Not run |
| AC-GLT-039 | A new schema with the seeded default administrator | The first snapshot is applied | The default administrator is deactivated | REQ-GLT-037 | Not run |

## E. Sign-in

| ID | Given | When | Then | Req | Status |
|---|---|---|---|---|---|
| AC-GLT-040 | SSO required, a platform session | The sign-in page opens | The person lands in the app with no password prompt | REQ-GLT-042 | Manual or staging |
| AC-GLT-041 | SSO required, no platform session | The page opens | One redirect to the platform login; on return without a session, a message and no second redirect | REQ-GLT-042 | Manual or staging |
| AC-GLT-042 | The person signs out in Finance | Done | The platform session ends; Agile and Macro sign out within about a minute; other Finance tabs sign out at once | REQ-GLT-043 | Manual or staging |
| AC-GLT-043 | A platform group | `POST /api/v1/auth/login` | `403 platformSignIn` | REQ-GLT-044 | Not run |
| AC-GLT-044 | A platform group | The database is searched | No password hash and no refresh token is stored for its people | REQ-GLT-044 | Not run |
| AC-GLT-045 | A signed-in person with no membership in any group | Calls Finance | `403 NOT_A_MEMBER` (authentication did not give access) | REQ-GLT-045 | Not run |
| AC-GLT-046 | `GET /api/auth/session` | Called without a platform cookie, and while SSO is not enabled | `401`, and `404` | REQ-GLT-041 | Not run |

## F. Roles and reach

| ID | Given | When | Then | Req | Status |
|---|---|---|---|---|---|
| AC-GLT-050 | A person with `gl:journal:view` and a role in Legal Entity A only | Asks for the journals of Legal Entity B in the same group by id | `404` (today this succeeds: finding F-02) | REQ-GLT-053 | Not run |
| AC-GLT-051 | The same person | Lists Business Groups, Legal Entities, ledgers, users | Only what is in reach (today every row: finding F-03) | REQ-GLT-054 | Not run |
| AC-GLT-052 | A person with no home node and no grant | Calls any list | Empty; no error | REQ-GLT-055 | Not run |
| AC-GLT-053 | Reach cannot be computed | A call | `503 SCOPE_UNAVAILABLE` | REQ-GLT-056 | Not run |
| AC-GLT-054 | A person with roles in two Legal Entities | Chooses the second in the selector | Every later call uses it; a Legal Entity outside reach answers `404` | REQ-GLT-057 | Not run |
| AC-GLT-055 | An Organization admin | Opens group-wide setup (business group, other Legal Entities) | Refused; their own Legal Entity's setup works | REQ-GLT-051 | Not run |
| AC-GLT-056 | An Admin-level person | Calls any Legal Entity of the group | Allowed (group reach) | REQ-GLT-055 | Not run |
| AC-GLT-057 | An administrator gives a person an extra Business Unit, then removes it | Done | Reach grows then shrinks; a grant from the platform cannot be removed in Finance (`409`) | REQ-GLT-059 | Not run |
| AC-GLT-058 | The only active Admin | Demoted, disabled, or removed | Refused | REQ-GLT-061 | Not run |
| AC-GLT-059 | A functional role is given in Finance and a new snapshot arrives with the same level | Applied | The role is kept | BR-GLT-034 | Not run |
| AC-GLT-060 | Row-level security is on and a query is run with the session setting for A | Run | Rows of B are not returned even by a query that forgets the filter | REQ-GLT-058 | Not run |

## G. Audit, jobs and operations

| ID | Given | When | Then | Req | Status |
|---|---|---|---|---|---|
| AC-GLT-070 | A write with a forged `X-User-Id: someone-else` | Made by person P | The audit row names P | REQ-GLT-070 | Not run |
| AC-GLT-071 | A refusal by scope | Happens | An audit row exists with the group and the person | REQ-GLT-072 | Not run |
| AC-GLT-072 | Two tenants and a job that fails in one | The job runs | The other tenant's run completes; the failure is logged with the group; the lease is released | REQ-GLT-075 | Not run |
| AC-GLT-073 | A job running longer than 15 minutes | Time passes | The lease is lost and another instance may run it | REQ-GLT-075 | Not run |
| AC-GLT-074 | A group with an ended subscription | The job list is built | The group is skipped | REQ-GLT-076 | Not run |
| AC-GLT-075 | Refusals, snapshots and pools | Counted | The three counters are present with their tags | REQ-GLT-080 | Not run |

## H. Hardening (no platform needed)

| ID | Given | When | Then | Req | Status |
|---|---|---|---|---|---|
| AC-GLT-090 | No token | `POST /api/v1/gl/setup-wizard/run` | `401` | REQ-GLT-090 | Not run |
| AC-GLT-091 | A person with no role in a Legal Entity | Logs in naming it | Refused | REQ-GLT-091 | Not run |
| AC-GLT-092 | The repository | Searched | No password value in `CLAUDE.md`, task definitions or properties; the stray root files are gone | REQ-GLT-092, 093 | Not run |
| AC-GLT-093 | The frontend | Searched | No Business Group id constant remains | REQ-GLT-094 | Not run |

## I. Moving an existing customer

| ID | Given | When | Then | Req | Status |
|---|---|---|---|---|---|
| AC-GLT-095 | The Orbinox and Unicon data in the shared database | Moved to their own group databases by the runbook | Row counts and balances equal before and after; a restore rehearsal passes; the old data is kept until the cut-over is confirmed | REQ-GLT-083 | Manual or staging |
