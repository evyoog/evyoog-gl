# Platform reference model: multi-tenancy and organization hierarchy as built in EIS, Agile Planner and Macro

Status: **reference, as built.** This is what Finance (GL) is asked to follow. Every statement is taken from the three product repositories (EIS `enterprise-intelligence-suite`, Agile `evyoog-thittam-agile`, Macro `evyoog-thittam-macro`) as of 3 Nov 2026. Source paths are given so each claim can be checked.
Nothing here has been run against a real Keycloak, the real platform webhook or the shared production database. All three products report "tested on a scratch PostgreSQL 16, not verified on real infrastructure". Finance should treat the same way.

Used by: [FRD](../../02-requirements/FRD/multi-tenancy-and-org-hierarchy/requirement.md), [workflows](../../04-workflows/multi-tenancy-and-org-hierarchy/README.md).

## 1. The idea in one page

1. **A group is the customer and the tenant.** One database per group. One schema per product inside it (Agile, Macro, Finance each get their own schema in the group's database).
2. **The tenant is decided by who signed in**, never by the address, host, a token claim or a header the caller controls. A person has memberships; a service (one product calling another) has grants.
3. **The platform (EIS) is the only writer of the structure and the people.** It sends each product a signed message (a snapshot) with the group's organizations, units, teams and members. Products read it and apply it; they never create organizations, units or people in a platform-managed group.
4. **The platform creates the database, schema and database roles; the product only runs its own migrations** (as a migration role) and serves requests as a runtime role.
5. **Five roles, the same in every product**, scoped by level. The person's home unit and any extra units decide what they can see (reach).
6. **One sign-in, one sign-out.** EIS is the only front door; products redeem the platform session and sign out through EIS.
7. **Fail closed.** If the registry, the entitlement or the scope cannot be decided, the request is refused (503), never served from a default.

## 2. Hierarchy

```
Group  (tenant; code is permanent; names the database and schemas)
 └─ Organization  (one is the "pilot" that administers the group)
     └─ Unit  (EIS table org_node; typed: Business Unit, Division, Department, Location, Cost Centre ...)
         └─ Unit ... (any depth, parents have a lower rank than children)
             └─ Team  (a record under a unit, not a node; members; no sub-teams; takes no seat)
                 └─ Members
```

| Rule | Source |
|---|---|
| A group is the table `tenant_group` (code `^[a-z][a-z0-9_]{1,29}$`, unique, immutable; status ACTIVE, EMPTY, SUSPENDED). Every organization is in exactly one group; an organization alone is a "group of one" | EIS FRD `group-provisioning-transfer`, BR-GRP-001/002 |
| An organization has exactly one root node (type ORGANIZATION). Sibling names are unique, ignoring case. A child's level rank is higher than its parent's. No cycles. Moves are recorded | EIS BR-ORG-003..009 |
| A team lives only under a unit, holds members only, has no sub-team, takes no seat | EIS BR-ORG-016..019 (C143) |
| The hierarchy is structural: it grants no permission by itself | EIS BR-ORG-014 |
| Deactivate, never delete: a unit or person missing from a later snapshot is deactivated in the product | EIS BR-PCN-006 |
| In a product, a group is one tenant; organizations are rows inside it, not tenants | Macro `CLAUDE.md`, Macro `INT-eis-platform-sync.md` section G |

In the product's own schema the tree is held as rows: Agile `org_unit` (kind GROUP, ORGANIZATION, UNIT) and `team`; Macro `org_nodes` and `teams`. Each row carries a platform reference (`group:<code>`, `org:<id>`, `node:<id>`) so a later snapshot updates the same row.

## 3. How the tenant is chosen (tenant resolution)

Order of the checks, as built in Agile (`TenantFilter`) and Macro (`MembershipTenantFilter`):

1. Read the person from the verified token. Match the membership by the identity provider's subject; match by e-mail only when the token says the e-mail is verified (Macro enforces this, Agile does not yet).
2. If the caller is a service, find the groups granted to its client (`azp`).
3. One group: use it. Several: a header `X-TENANT-ID` may only **choose among the caller's own**; a group that is not theirs answers like a group that does not exist. No header: the default membership. None: refuse.
4. Check the group is active, the member is active, the subscription is active or in trial and has not ended, the member has product access, and the tenant is READY.
5. Bind the request to that tenant's connection and schema (taken from the registry, never from the request).

Refusal codes (Agile `tms-backend.md` section 5k-4; Macro `INT-eis-platform-sync.md`):

| Code | Meaning | Status |
|---|---|---|
| `REGISTRY_UNAVAILABLE` | registry cannot be read and no earlier answer is held | 503 |
| `NOT_A_MEMBER` | no membership, or not in the named group; a service without a grant gets `NO_GRANT` | 403 |
| `GROUP_CHOICE_REQUIRED` | several groups and none chosen, no default | 403 |
| `ORG_INACTIVE` | the group or organization is not active | 403 |
| `USER_INACTIVE` | membership disabled | 403 |
| `NO_SUBSCRIPTION`, `SUBSCRIPTION_NOT_ACTIVE`, `SUBSCRIPTION_ENDED` | subscription rules; the end date is checked on every request | 403 |
| `NO_PRODUCT_ACCESS` | the person has no access to this product | 403 |
| `NOT_PROVISIONED` / `TENANT_NOT_READY` | schema not ready yet | 503 |
| `TENANT_UNAVAILABLE` (Macro), `SUBSCRIPTION_NOT_ACTIVE` (Agile) | suspended | 403 |
| `SCOPE_UNAVAILABLE`, `ENTITLEMENT_UNAVAILABLE` | the scope or entitlement fact cannot be read | 503 |

Registry answers are cached for 60 seconds per instance, so a revoked access takes effect within a minute. The registry is read fail closed: with no readable registry and nothing cached, the request is refused.

## 4. The registry (control data)

Ten tables, the same names in EIS (adapted), Agile and Macro:

| Table | Holds |
|---|---|
| `platform_group` | code (unique), name, status ACTIVE, SUSPENDED, CANCELLED, `platform_managed`, `snapshot_version` |
| `platform_product` | the products (AGILE, MACRO ...) |
| `platform_subscription` | group x product: status TRIAL, ACTIVE, SUSPENDED, CANCELLED; plan; seats; `ends_at` |
| `platform_placement` | where a group's schema lives: `tenant_key`, JDBC URL, user names, schema, **the name of an environment variable or a secret reference for the password, never the password**, migration user, `state`, current and failed step, schema version, attempts, last error |
| `platform_membership` | person x group: subject and/or e-mail, status ACTIVE or DISABLED, default flag, product access, source, org role, level (GROUP_ADMIN, ORG_ADMIN, UNIT_ADMIN, MEMBER), home organization and node |
| `platform_service_grant` | a service client x group |
| `platform_setting` | `tenancy.mode` (SINGLE or ROUTED), `entitlement.enforce` |
| `platform_role_map` | platform role to the product role (`app_role`) |
| `platform_job_lease` | one lease per scheduled job and tenant |
| `platform_org` | the organizations of a group |

In each tenant schema there is also `platform_node_access` (extra units given to a person: source PLATFORM, AGILE or MACRO). Agile keeps the registry in its own schema until a separate platform database exists; Macro keeps it in a `platform_control` schema with its own migration tree.

Placement states: REQUESTED, VALIDATING, DB_CREATING, DB_READY, SCHEMA_CREATING, MIGRATING, SEEDING, SYNCING, VERIFYING, **READY**, FAILED, SUSPENDED, DECOMMISSIONING, DECOMMISSIONED. Only READY serves requests and runs jobs.

## 5. Provisioning and the signed webhook

The platform creates, in its own steps: the group's database, a schema per product, and three roles per schema: `<schema>_rt` (runtime, data changes only), `<schema>_mig` (owns the schema, runs migrations), `<schema>_xfer` (transfer). Passwords travel only as references (`local:`, `aws:`, `env:`, `file:`). The product never connects to a host, database or schema named in a message: host, port and SSL come from the product's own environment, and the names are recomputed from the group code and compared.

Delivery: `POST /api/internal/platform/webhook` (Agile and Macro). No bearer token.

| Item | Value |
|---|---|
| Headers | `X-EIS-Event-Id`, `X-EIS-Event-Type`, `X-EIS-Timestamp` (epoch seconds), `X-EIS-Signature: sha256=<hex>` |
| Signature | HMAC-SHA256 over `timestamp + "." + body`, key from the environment variable `PROVISIONING_WEBHOOK_SECRET` |
| Receiver | constant-time compare; reject a timestamp more than 300 s off; answer 404 while the secret is empty; 401 on a bad or stale signature |
| Answer | `{state: DONE, READY, MIGRATING, SEEDING, SYNCING, RUNNING or FAILED, schemaVersion, snapshotVersion, detail}`; an empty body means DONE; the platform retries on any non-2xx and polls on `RUNNING` |
| Message types | `schema_provision_requested`, `schema_status`, `provision_requested`, `suspend`, `resume`, `deprovision_requested`, `group_snapshot` |
| Retry | snapshots 1, 5, 15, 60 and 240 minutes, then stop; outbox events up to 10 attempts then FAILED with an admin alert |

What the product does on `schema_provision_requested`: verify the names against the group code, refuse a group code that collides with a hand-made tenant, record the placement as REQUESTED, then in the background validate the schema, run its migrations as the migration role, and verify as the runtime role (no superuser, no create-database, no create-role, no replication, no bypass of row security, cannot create objects in its own schema or in `public`, cannot read another product's schema). Only then the placement becomes READY. A group is migrated under an advisory lock so a second instance answers RUNNING.

**`group_snapshot`** is the full truth, applied in one transaction. It carries: the group (code, name, pilot organization), the subscribed products, the levels (`type`, `label`, `rank`), the organizations (id, code, name, status, pilot flag, attributes type, industry, country, region, subscription status, plan, seats, trial, expiry), the nodes (id, organization, parent, name, type, code, sort order, active), the members (subject if known, e-mail, names, status, organization, node, org role, product role, `groupAdmin`, `unitAdminOf[]`, `teamLeadOf[]`) and the teams (id, node, name, members with LEAD or MEMBER). The version is a SHA-256 hash of the content; the same version is "unchanged". Never sent: passwords, tokens, MFA, payment, tax or support data.

Apply rules: anything missing from a later snapshot is **deactivated, never deleted**; the snapshot never creates business records; a snapshot with no `levels` (an older platform) may add people and units but must not change roles, home units or grants.

## 6. Roles and reach

Five roles, the same level in every product (EIS `BR-ROL-001`):

| Shown | Scope | Stored in products | Stored in EIS |
|---|---|---|---|
| Admin | the whole group | `ADMIN` | `GROUP_ADMIN` |
| Organization admin | one organization | `ORG_ADMIN` | `ORG_ADMIN` |
| Unit admin | one unit and below | `UNIT_ADMIN` | `UNIT_ADMIN` |
| Team Lead | one team | `TEAM_LEAD` | `TEAM_LEAD` |
| User | ordinary person | `USER` | `MEMBER` |

- Only Admin is a group administrator. Organization admin and Unit admin hold the Admin permissions **except** the group-wide one (`MANAGE_GROUP` in Agile, `ADMINISTER_GROUP` in Macro) and are limited by unit scope.
- The role is decided on the server from live data. A role, organization or unit id sent by the browser is a request, not a fact.
- A person's platform role is mapped to the product's role by `platform_role_map`, and an unknown role maps to the least-privileged one. A role of another product never grants a role.
- **Who owns what.** The platform owns the five levels, the unit placement and the unit-scoped grants. The product owns what the platform cannot know: Macro owns work roles (`PROJECT_OWNER`, `ACTIVITY_USER`, `TASK_USER`) and the permission and screen matrices; Agile owns the role-to-permission matrix (`role_permission`) and screen access. In a platform-managed group the product refuses to change a platform-managed person's level (`409 PLATFORM_MANAGED`).
- **Reach (scope).** A person reads the subtree of their home unit plus the subtrees of units they were given (`platform_node_access`). Group administrators are unlimited in Agile; in Macro even Admin is limited unless placed at the group node (the two products differ here, see section 9). A person with no unit reads nothing. Data outside reach looks like data that does not exist (404), data inside reach but without the right is 403.
- Scope is enforced in application code and, in Agile and Macro, also by PostgreSQL row-level security on the main tables. Neither is the tenant boundary: the separate database and role are.
- The person can see their own reach: `GET /v1/me/context` and `GET /v1/access/overview` (Agile), `GET /api/access/context` and `/api/access/overview` (Macro).

## 7. One sign-in and one sign-out

- EIS keeps a cookie `vyoog_sso` (HttpOnly, SameSite Lax, domain `.evyoog.com` live, empty locally, Secure in live) and a refresh cookie `eis_rt`.
- A product redeems the platform session backend to backend: `POST {EIS_BACKEND_URL}/internal/sso/token` with header `X-Internal-Sso-Secret` (the shared secret `INTERNAL_SSO_SHARED_SECRET`, the same value in EIS and every product) and the body `{ssoSessionId, targetClientId}`. The answer is a token for the product's own identity client. The cookie value is never returned to the browser.
- The product exposes `GET /api/auth/sso-config` (`{enabled, required, loginUrl, logoutUrl}`) and `GET /api/auth/session`. With `required=true` the product shows no password form and redirects once to the EIS login.
- Sign-out goes to `{logoutUrl}?redirect=<app>/home`; EIS ends the platform session and tells partner backends (`products.sso_backend_url`); other tabs sign out together and the product notices within about one minute or on the next 401.
- EIS sends the person back to a product only if the address's origin equals the origin of a catalog product's `launchUrl` (no open redirect).
- Authentication never implies access: after sign-in the tenant checks of section 3 still apply. Not closed yet: a direct password grant to the identity provider is still possible for a caller who knows it.

## 8. Entitlement and the scheduled jobs

- Entitlement is derived, never stored: an active subscription in term, the plan's included features, and the member's product access. EIS answers `POST /internal/entitlements/check` (same shared-secret header). Products decide locally and, for sensitive actions, may ask the platform and fail closed. A term ended with grace gives READ_ONLY access.
- Scheduled jobs run once per tenant: the job lists the groups with a current subscription and a READY placement and takes a lease per job and tenant (`platform_job_lease`) with one atomic statement on the database clock. A run longer than 15 minutes loses its lease. A failure in one tenant is logged with the group and does not stop the others.

## 9. Where the products differ (decisions Finance must make for itself)

| Topic | Agile | Macro |
|---|---|---|
| E-mail match | by e-mail even if unverified | only if the token says verified |
| Reach of Admin | unlimited in its group | limited by unit unless placed at the group node |
| Who provisions | the platform; Agile only migrates (its own provisioner was removed in CR-222) | the platform; Macro validates, migrates and verifies |
| Tenant mechanism | its own routing data source | Hibernate multi-tenancy with a routing connection provider |
| Product roles | Admin, Org admin, Unit admin, Team Lead, User with a configurable permission matrix | the same five, plus work roles for people at level User |
| Entitlement gate | on by default | off by default; scope on by default in platform groups |
| Schema names | `vyg_agile_<group>` in documents; code follows the platform's names | `macro_<group>`; documents still say `vyg_macro_<group>` |
| Structure writes | read-only while platform-managed (409), changing to EIS only (CR-247 planned) | written to the platform first (write-through), 503 `PLATFORM_UNAVAILABLE` if it cannot be reached |

## 10. Lessons already paid for (do not repeat in Finance)

1. A query run inside a transaction while an entity is half changed makes Hibernate flush the half state. Bring the entity to a valid state before any query (Agile board rename, Finance V30b hit the same).
2. A `JdbcTemplate` inside a JPA transaction gets the tenant's connection; read the registry on its own connection.
3. A seeded default admin appears in every new schema: the first snapshot must deactivate it.
4. A person's Keycloak `tenant` attribute holds one value per product; do not treat another product's value as a mismatch. Claims about the tenant are informational only.
5. Two platforms sending snapshots to one group reset people to User every few seconds: the "older platform" guard (no `levels`, no role changes) exists for this.
6. First unit directly under the organization was lost by a root merge: only a node of type ORGANIZATION with no parent is a root.
7. Naming conflict between documents and code (`eis_<group>`, `vyg_*` against `<group>_db`, `<schemaCode>_<group>`): the platform's code is the truth. Finance must take names from the platform message and recompute them from the group code, not hard-code a pattern.
8. Row-level security with bare `current_setting()` calls is 4 to 25 times slower: wrap them in `(SELECT ...)`.
9. Tests must not touch shared databases; the isolation suite runs two tenants with their own non-superuser roles over real HTTP.

## 11. Known gaps in the reference (what is not claimed)

- Not verified on a real identity provider, real platform, real RDS or customer data volume. The token exchange needs Keycloak permissions documented by the platform.
- Organization-level isolation **inside** a group schema is not the tenant boundary and not yet complete: in Agile, org, team, user and audit tables are outside row-level security; in Macro only four tables are covered, off by default, and users, teams, reports and the audit log are not level-limited.
- No grant message in contract v1 for grants given inside a product.
- EIS still contains handlers for `unit.*` and `org.update` tool changes that decision C144 says must be refused.
- The shared SSO secret is to be replaced by one service client per application (backlog).
- Platform documents and code disagree on database and schema names (see lesson 7) and on the levels in the snapshot (the code publishes ORGANIZATION and BUSINESS_UNIT only, without the root node, plus `teams[]` and `teamLeadOf[]`).
- Agile's change-request log has two entries numbered CR-246 and two numbered CR-247 (one built, one planned); the planned ones should be renumbered.

## 12. Sources

- EIS: `docs/02-requirements/FRD/{org-hierarchy, multi-tenant-support, group-management, group-provisioning-transfer, platform-tool-sync, product-connection, member-role-assignment, organization-directory, entitlements, cross-app-sign-in}`; `docs/03-business-rules/BR-INT-001`, `BR-ROL-001`, `BR-ROL-002`; `docs/09-integrations/platform-product-connection-v1.md`, `platform-tool-sync-plan.md`.
- Agile: `docs/08-architecture/backend-architecture/tms-backend.md` sections 4, 5k, 5l; `docs/11-release-notes/CHANGE_REQUESTS.md` CR-180 to CR-191, CR-211, CR-214, CR-222 to CR-227, CR-231 to CR-252.
- Macro: `docs/08-architecture/integrations/{INT-eis-platform-sync, INT-eis-org-write-through, macro-multitenancy-alignment-plan, macro-roles-reach-permissions-plan, platform-product-connection-v1}.md`; `docs/08-architecture/database/database-design.md`.
