# Hierarchy mapping: platform Group → Legal Entity → Business Unit

Status: **proposal for the Domain SME to confirm.** The mapping of the three top levels follows the product owner's instruction ("group → legal entity → business units, likewise everything"). Everything marked *Decision* is open.
Read with: [platform-reference-model.md](platform-reference-model.md) (what the platform sends) and [finance-current-state.md](finance-current-state.md) (what Finance has).

## 1. The mapping

| Platform (EIS, Agile, Macro) | Finance (GL) | Notes |
|---|---|---|
| **Group**: the customer and the tenant. One database per group | **Business Group** (`gl.business_group`), plus the single **Consumption Context** row that records the customer | One platform group is exactly one Business Group. The group code names the database and schema |
| **Organization**: a company in the group | **Legal Entity** (`gl.legal_entity`) | One platform organization is exactly one Legal Entity. The legal entity is Finance's accounting boundary: ledger, period status, approval policy, reporting |
| **Unit** of type Business Unit | **Business Unit** (`gl.business_unit`) | 1 BU = 1 state = 1 GSTIN stays true; see section 4 |
| **Unit** of any other type (Division, Department, Location, Cost Centre) | not a Finance level | Kept as a path in a mirror of the platform tree so that reach can be decided, see section 5. A Cost Centre in the platform is not the same thing as a Finance Cost Centre dimension value; Finance keeps its own |
| **Team** (a record under a unit; no sub-teams) | not mapped | Finance has no team. Team leads are people; the Team Lead level is applied through the person's role |
| Inventory Organisation, Sub-inventory | Finance only, below the Business Unit | Created and edited in Finance; the platform does not know them |
| **Member** (person) | `auth.users` row plus role assignments | Keyed by the identity provider subject, not by password. See section 6 |

```
Platform                     Finance
Group  ──────────────────►  Business Group   (one per tenant database; es_mode, currency are Finance's)
 └─ Organization  ────────►   └─ Legal Entity       (accounting standard, TAN: Finance's)
     └─ Unit (BUSINESS_UNIT) ───►  └─ Business Unit   (GSTIN, state code: Finance's)
         └─ Unit (other types)         (mirror only, for reach)
             └─ Team                   (not mapped)
                                         └─ Inventory Organisation ─ Sub-inventory   (Finance only)
```

Where a platform group has no real organization (a "group of one", an individual customer), the platform still sends one organization; it maps to one Legal Entity.

## 2. Who masters what (single writer)

The platform is the only writer of existence, name, parent, active state and people. Finance is the only writer of accounting attributes. This follows the platform rule that a product never creates organizations, units or people in a platform-managed group.

| Object | Mastered by the platform | Mastered by Finance |
|---|---|---|
| Business Group | group code, name, status | `es_mode` (THIN_ES, THICK_ES), default currency, COA structure choices |
| Legal Entity | organization id, code, name, status (active or not), the group it belongs to, `attributes` (type, industry, country, region) | accounting standard (IND_AS, IGAAP, IFRS, US_GAAP), TAN, ledger assignment, calendars, period configuration |
| Business Unit | node id, code, name, parent organization, active state | GSTIN, state code, any inventory organisations below |
| Person | subject, e-mail, names, status, home organization and unit, level (Admin, Organization admin, Unit admin, Team Lead, User), units they administer | functional role (GL Manager, Accountant, Approver, Viewer, Auditor) and approval authority |
| Subscription | plan, seats, start and end, status | nothing |

Consequences:
1. In a platform-managed group, Finance's endpoints that create, rename, move or delete a Business Group, Legal Entity or Business Unit answer `409 PLATFORM_MANAGED`, except the Finance-owned attributes in the right-hand column, which stay editable.
2. A Legal Entity or Business Unit created from a snapshot starts with its Finance attributes **incomplete**. It cannot post journals (Legal Entity) or carry GST transactions (Business Unit) until the Finance attributes are filled. *Decision D-03* covers the exact gate.
3. A unit or organization that disappears from a later snapshot is **deactivated, never deleted**. Posted journals keep their references. Finance already uses soft delete (`is_active`), so this fits without change.
4. The platform does not know Finance's ledgers, calendars, chart of accounts or dimensions. They stay inside the Legal Entity and are never sent to the platform.

## 3. Group and Business Group

- The group code is permanent and names the database and the schema. Finance stores it on the Business Group and on the registry row, never edits it.
- Finance's `es_mode` (THIN_ES or THICK_ES) is a Finance setting. **Thin ES allows exactly one Legal Entity** (rule `THIN_ES_LE_LIMIT`). A platform group with more than one organization cannot be Thin. Rule: when a snapshot brings a second organization to a Thin ES Business Group, the snapshot is **not** rejected (the platform is the single writer and Finance must accept the structure) and the Business Group is marked "needs Thick ES" and blocks posting on the new Legal Entity until a Finance administrator switches it. *Decision D-02.*
- One Consumption Context row per tenant database holds the customer's name and contact. It is created by the provisioning step from the group name, not by the open setup wizard.

## 4. Business Unit and GSTIN

- Finance's rule is "1 BU = 1 State = 1 GSTIN". The platform node of type BUSINESS_UNIT has no state or GSTIN. Finance asks a Finance administrator to complete it (state code, GSTIN, validated by the existing GSTIN pattern).
- Since V32 the same GSTIN may be used by several Business Units of one Legal Entity (for example CBE-1 and CBE-2 in Tamil Nadu). That stays; it is a Finance rule.
- A BUSINESS_UNIT node nested under another unit (Division, Department) still becomes a Business Unit directly under its Legal Entity; the path is kept in the mirror. *Decision D-04: confirm that Finance's Business Unit level is flat under the Legal Entity.*

## 5. Mirror of the platform tree (for reach)

To decide what a person may read, Finance needs the platform's tree, not only Legal Entities and Business Units. It keeps a table of the nodes it received (id, parent, type, name, platform reference such as `org:<id>`, `node:<id>`) and, for each node, the Legal Entity and Business Unit it belongs to. A person's **home node** and the nodes they administer decide the Legal Entities and Business Units they can reach (see the FRD, section on reach).

## 6. People and roles

The platform sends five levels. Finance's own roles (`GL_MANAGER`, `GL_ACCOUNTANT`, `GL_APPROVER`, `GL_VIEWER`, `GL_AUDITOR`) describe what a person does in the ledger, not where they sit. Following Macro's "work roles", the two are kept apart:

| Platform level | Scope in Finance | What the person may do in Finance |
|---|---|---|
| **Admin** (group admin) | the whole group (every Legal Entity) | set up the group's finance structure and users; the role planned in Finance `CLAUDE.md` as `GL_ADMIN`, at business-group scope |
| **Organization admin** | one Legal Entity and its Business Units | set up and administer that Legal Entity (ledger, calendar, COA, dimension values, periods, users) |
| **Unit admin** | one Business Unit (and below) | administer that Business Unit's data and people, no group or legal-entity setup |
| **Team Lead** | their unit | an ordinary person who also leads a team; gets a Finance functional role |
| **User** | their unit | an ordinary person with a Finance functional role |
| *Platform operator* (`SYS_ADMIN` in Finance, `eis_platform_admin` in EIS) | none inside any group | creates nothing in a group and gets no group rights, as in EIS |

Rules (these are the platform rules, written for Finance):
1. Only the Admin level is a group administrator. Organization admin and Unit admin hold the Admin permissions except the group-wide ones, and are limited by reach.
2. Admin levels come from the platform on every snapshot and cannot be changed in Finance in a platform-managed group (`409 PLATFORM_MANAGED`).
3. A person's functional role (accountant, approver, viewer, auditor, manager) is assigned in Finance, within the person's reach, by someone who is an administrator of that Legal Entity or above. It is applied when the person is new or when the platform changes their level, so a role given in Finance is not overwritten on every snapshot (the Agile rule).
4. The existing approval rules (approver role by Legal Entity, Business Unit and source) keep working and now use the person's Finance functional role.
5. The existing permission strings (`gl:journal:approve` and so on) stay. The level decides **where** a person acts; the functional role decides **what** they may do there. Both must allow the action.

*Decision D-05: confirm that the five Finance roles stay as functional roles and that `GL_ADMIN` is the platform's Admin level at group scope, not a stored role.*

## 7. Decisions needed from the Domain SME

| ID | Decision | Recommendation |
|---|---|---|
| D-01 | Group = Business Group, one to one | Yes (as instructed) |
| D-02 | A platform group with several organizations and a Thin ES Business Group | Accept the snapshot, mark the group as needing Thick ES, block posting on the extra entity until switched |
| D-03 | What is blocked while a Legal Entity or Business Unit has incomplete Finance attributes | Legal Entity: no ledger assignment, no posting. Business Unit: no GST transaction. Everything else works |
| D-04 | Business Unit stays flat under the Legal Entity, whatever the platform nesting | Yes |
| D-05 | Platform levels plus Finance functional roles, `GL_ADMIN` as a level | Yes |
| D-06 | Whether a Unit admin or User of one Business Unit may read journals of another Business Unit of the same Legal Entity | Phase 1: no limit inside a Legal Entity (the ledger is one book). Phase 2: Business Unit reach on reports and journal listing if the business needs it |
| D-07 | Whether a platform Team Lead gets any Finance-specific power | No; the Team Lead level has no extra Finance rights |
