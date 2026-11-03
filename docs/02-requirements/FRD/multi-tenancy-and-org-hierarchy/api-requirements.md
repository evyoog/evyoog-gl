# API requirements: multi-tenancy and organization hierarchy (Finance)

Status: **Draft.** Nothing here is built. Rules are in [business-rules.md](business-rules.md); requirement ids in [requirement.md](requirement.md).
Finance's API rules are kept: responses use the `ApiResponse<T>` envelope, errors follow `{status, code, message, field, timestamp}`, new endpoints are under `/api/v1/...`, entities are never returned. Where the platform contract fixes a path (the webhook, SSO), the platform's path is used so the platform needs no special case.
No endpoint, path, method or payload that exists today is changed by this document except where a section says "changes".

## 1. New endpoints

### 1.1 Platform connection (no bearer token)

| Method and path | Purpose | Requirement |
|---|---|---|
| `POST /api/internal/platform/webhook` | Receives the platform's signed messages (provision, status, snapshot, suspend, resume, deprovision) | REQ-GLT-020..025 |

Headers: `X-EIS-Event-Id`, `X-EIS-Event-Type`, `X-EIS-Timestamp`, `X-EIS-Signature: sha256=<hex>`. Content type `application/json`.

| Case | Answer |
|---|---|
| No secret configured | `404` |
| Signature missing, wrong or stale (more than 300 s) | `401` |
| Message acceptable | `200 {state, schemaVersion?, snapshotVersion?, detail?}` where `state` is `DONE`, `READY`, `MIGRATING`, `SEEDING`, `SYNCING`, `RUNNING` or `FAILED`; an empty body means `DONE` |
| Message not acceptable (for example a name that does not match the group code) | `200 {"state":"FAILED","detail":...}` |
| Unexpected error | `5xx` (the platform retries) |

`group_snapshot` body (platform contract v1, fields Finance reads): `contractVersion`, `messageType`, `eventId`, `occurredAt`, `snapshotVersion`, `product{id,name,schemaCode}`, `group{code,name,pilotOrganizationId}`, `subscribedProducts[]`, `levels[{type,label,rank}]`, `database{name}`, `schema{name}`, `organizations[{id,code,name,status,pilot,attributes{type,industry,country,region},subscription{status,planName,seats,trial,expiresAt}}]`, `nodes[{id,organizationId,parentId,name,type,code,sortOrder,active}]`, `members[{sub?,email,firstName,lastName,status,organizationId,nodeId,orgRole,productRole,groupAdmin?,unitAdminOf[]?,teamLeadOf[]?}]`, `teams[{id,organizationId,nodeId,name,canInvite,members[{sub?,email,firstName,lastName,role}]}]`. `members[].sub` may be absent for a person who has not signed in; they are matched by e-mail and the subject is filled on first sign-in. Finance ignores `teams`.

### 1.2 Sign-in (no bearer token)

| Method and path | Purpose | Requirement |
|---|---|---|
| `GET /api/auth/sso-config` | `{enabled, required, loginUrl, logoutUrl}`; `logoutUrl` is `EIS_LOGOUT_URL`, else the origin of `EIS_LOGIN_URL` plus `/signout` | REQ-GLT-041 |
| `GET /api/auth/session` | Reads the `vyoog_sso` cookie, redeems it at the platform, answers `{access_token, refresh_token?, expires_in, sso: true}`; `401` when there is no usable platform session; `404` while SSO is not enabled; rate limited per address | REQ-GLT-041 |

SSO is enabled only when `PLATFORM_SSO_ENABLED=true` and both `EIS_BACKEND_URL` and `INTERNAL_SSO_SHARED_SECRET` are set. Finance calls `POST {EIS_BACKEND_URL}/internal/sso/token` with header `X-Internal-Sso-Secret` and body `{ssoSessionId, targetClientId}`.

### 1.3 Context and access (signed-in)

| Method and path | Purpose | Permission | Requirement |
|---|---|---|---|
| `GET /api/v1/me/context` | The caller's context, read-only: `{group{code,name}, groups[] (when several), activeLegalEntity{id,name}, legalEntities[{id,name,businessUnits[...]}], level, functionalRoles[], scope{enforced, limited, legalEntityCount, businessUnitCount}, platformManaged, disabled, permissions[], screens[]}` | any signed-in | REQ-GLT-057, 060 |
| `PUT /api/v1/me/active-legal-entity` | Choose the active Legal Entity `{legalEntityId}`; `404` when outside reach | any signed-in | REQ-GLT-057 |
| `GET /api/v1/access/overview` | For administrators: each person with level, home unit, Legal Entities and Business Units reached, grants and their source | `gl:users:view` and an administrator level | REQ-GLT-060 |
| `POST /api/v1/access/grants` `{userId, legalEntityId or businessUnitId}` | Give an extra Legal Entity or Business Unit; `201` | administrator, within own reach | REQ-GLT-059 |
| `DELETE /api/v1/access/grants/{userId}/{nodeId}` | Remove one; `204`; `409` if the grant came from the platform ("remove it there") | administrator, within own reach | REQ-GLT-059 |

Header `X-TENANT-ID` (group code or tenant key) may accompany any `/api/v1` call and chooses only among the caller's own groups (BR-GLT-004).

### 1.4 Finance-owned attributes of platform-managed structure

These already exist as endpoints and keep their paths; the table shows what changes for a platform-managed group (section 2).

| Existing endpoint | Platform-managed group |
|---|---|
| `PATCH /api/v1/gl/legal-entities/{id}` | Only `accountingStandard`, `tan` accepted; `name`, `code`, `businessGroupId`, `isActive` answer `409 PLATFORM_MANAGED` |
| `PATCH /api/v1/gl/business-units/{id}` | Only `gstin`, `stateCode` accepted |
| `PATCH /api/v1/gl/business-groups/{id}` | Only `esMode`, `defaultCurrency` accepted |
| `/api/v1/gl/inventory-organisations/**`, `/api/v1/gl/sub-inventories/**` | Unchanged; Finance only |

### 1.5 Operations (inside the product, group scoped)

| Method and path | Purpose | Requirement |
|---|---|---|
| `GET /actuator/metrics` (counters) | `gl.tenancy.refusals` (tag `code`), `gl.platform.sync` (tags `type`, `status`), `gl.tenancy.pools` | REQ-GLT-080 |

## 2. Changes to existing endpoints

| Endpoint | Change | Requirement |
|---|---|---|
| Every `/api/v1/**` | Runs the tenant gate and the scope check first; the caller's Legal Entity is checked against reach. A Legal Entity id outside reach answers `404` | REQ-GLT-053 |
| Every list (`GET` on business-groups, legal-entities, ledgers, legal-entity-ledgers, users, roles, approval-policies, audit-log, and the rest) | Returns only what is within reach. A call with no filter no longer returns every row of the schema | REQ-GLT-054 |
| `POST /api/v1/gl/business-groups`, `/legal-entities`, `/business-units`, and their rename, move and delete | In a platform-managed group: `409 PLATFORM_MANAGED` | REQ-GLT-033 |
| `POST /api/v1/gl/setup-wizard/run` | No longer `permitAll`. Needs authentication and a group-level permission. In a platform-managed group the wizard may not create a Business Group, Legal Entity or Business Unit | REQ-GLT-090 |
| `POST /api/v1/auth/login`, `/refresh`, `/change-password`, `/users/{id}/reset-password` | Off in a platform group: `403 platformSignIn` | REQ-GLT-044 |
| `POST /api/v1/auth/users`, `PATCH /api/v1/auth/users/{id}` | In a platform-managed group the person comes from the platform; Finance only assigns functional roles. Creating a person refused (`409 PLATFORM_MANAGED`) | BR-GLT-017 |
| `POST/DELETE /api/v1/auth/users/{id}/roles` | Assigns a **functional** role within the assigner's reach; a level change is refused (`409 PLATFORM_MANAGED`) | BR-GLT-033, 034 |
| `/api/v1/gl/journals` (create) | The Legal Entity must be within reach and complete (BR-GLT-023); the posting rules are unchanged | REQ-GLT-034, 053 |
| Controllers using `@RequestHeader("X-User-Id")` | The header is ignored; the actor is the token's subject | REQ-GLT-070 |
| `/api/v1/auth/me` | Superseded by `/api/v1/me/context`; kept for the existing frontend during the move | |
| CORS | The allowed origins move from code to configuration, per environment | REQ-GLT-084 |

## 3. Refusal bodies

```json
{ "status": 403, "code": "NOT_A_MEMBER", "message": "You are not a member of this group.", "field": null, "timestamp": "2026-11-03T10:30:00Z" }
```

The codes are those of BR-GLT-010. A change the platform owns:

```json
{ "status": 409, "code": "PLATFORM_MANAGED", "message": "This is managed in the eVyoog platform. Change it there.", "field": null, "timestamp": "2026-11-03T10:30:00Z" }
```

## 4. Registry tables Finance reads (not an HTTP API)

The registry is the ten `platform_*` tables of the [reference model](../../../08-architecture/multi-tenancy/platform-reference-model.md), plus `platform_node_access` and the mirror of the platform tree inside each tenant schema. Their exact placement (control schema or control database) is open decision D-07 in the plan.
