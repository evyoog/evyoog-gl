# Finance (GL) — Documentation Index

Living documentation for **eVyoog Finance (General Ledger)**, repository `evyoog/evyoog-gl` (backend) with its frontend `evyoog/evyoog-frontend`. The structure follows the Agile Planner documentation: numbered folders from business to release notes, one folder per stage, and the same names for a feature in every stage.

The authoritative rules for implementing code are in the root `CLAUDE.md`; this folder holds the requirements, workflows and architecture. Where this folder disagrees with `CLAUDE.md`, the difference is listed as a decision (see [the plan](08-architecture/multi-tenancy/gap-analysis-and-adoption-plan.md), D-10), not silently changed.

## Where things are

| Folder | Contents |
|---|---|
| [01-business/](01-business/) | Empty. Product vision and capability list for Finance are not yet written down as documents |
| [02-requirements/](02-requirements/) | `FRD/<feature>/` requirement, business rules, API, UI and acceptance criteria. Today: [multi-tenancy-and-org-hierarchy](02-requirements/FRD/multi-tenancy-and-org-hierarchy/requirement.md) |
| [03-business-rules/](03-business-rules/) | Empty. Cross-feature rules are in the FRD folders and in `CLAUDE.md` |
| [04-workflows/](04-workflows/) | `<feature>/` workflows. Today: [multi-tenancy-and-org-hierarchy](04-workflows/multi-tenancy-and-org-hierarchy/README.md) (WF-01 to WF-08) |
| [05-ui/](05-ui/) | Empty. UI requirements for a feature are in its FRD folder |
| [06-api/](06-api/) | Empty. If springdoc output is published, put the spec under `06-api/openapi/` |
| [07-database/](07-database/) | Empty. The schema is in `src/main/resources/db/migration` |
| [08-architecture/](08-architecture/) | `multi-tenancy/`: [as-is analysis](08-architecture/multi-tenancy/finance-current-state.md), [platform reference model](08-architecture/multi-tenancy/platform-reference-model.md), [hierarchy mapping](08-architecture/multi-tenancy/hierarchy-mapping.md), [gap analysis and plan](08-architecture/multi-tenancy/gap-analysis-and-adoption-plan.md) |
| [09-integrations/](09-integrations/) | Empty. The platform connection is specified in the multi-tenancy FRD until a separate contract page exists |
| [10-user-manual/](10-user-manual/) | Empty |
| [11-release-notes/](11-release-notes/) | Empty. `../TECHNICAL_DEBT.md` is the debt register |
| [functional-specs/](functional-specs/) | `Finance_All_Capabilities_Feature_wise_Basic_and_Detailed.docx` (existing) |
| [status/](status/) | `Finance_Capability_Completion_Status.docx` (existing) |

Empty folders are part of the standard and are filled only when real content exists. Do not add placeholder documents.

## Features

| Feature | Status | Start here |
|---|---|---|
| Multi-tenancy and organization hierarchy (group, Legal Entity, Business Unit) | Draft for Domain SME review; nothing built | [requirement.md](02-requirements/FRD/multi-tenancy-and-org-hierarchy/requirement.md) |

### Multi-tenancy and organization hierarchy: reading order

1. [finance-current-state.md](08-architecture/multi-tenancy/finance-current-state.md): what Finance does today and the 18 findings.
2. [platform-reference-model.md](08-architecture/multi-tenancy/platform-reference-model.md): how EIS, Agile and Macro do it.
3. [hierarchy-mapping.md](08-architecture/multi-tenancy/hierarchy-mapping.md): Group, Legal Entity, Business Unit and who owns what.
4. The FRD: [requirement](02-requirements/FRD/multi-tenancy-and-org-hierarchy/requirement.md), [business rules](02-requirements/FRD/multi-tenancy-and-org-hierarchy/business-rules.md), [API](02-requirements/FRD/multi-tenancy-and-org-hierarchy/api-requirements.md), [UI](02-requirements/FRD/multi-tenancy-and-org-hierarchy/ui-requirements.md), [acceptance criteria](02-requirements/FRD/multi-tenancy-and-org-hierarchy/acceptance-criteria.md).
5. [Workflows](04-workflows/multi-tenancy-and-org-hierarchy/README.md): onboarding, sign-in, sign-out, hierarchy sync, role and scope check, service calls, subscription lifecycle, moving an existing customer.
6. [gap-analysis-and-adoption-plan.md](08-architecture/multi-tenancy/gap-analysis-and-adoption-plan.md): phases, sizes, decisions D-01 to D-14, risks.

## Traceability

Every feature is traced **Requirement → Business rule → Workflow → UI → API → Database → Frontend → Backend → Test case → UAT → Release**. Requirement ids are `REQ-GLT-nnn`, rules `BR-GLT-nnn`, workflows `WF-nn`, UI `UI-GLT-nnn`, acceptance criteria `AC-GLT-nnn`; Finance capability ids (GL-01 and so on) are used where a capability is named. Keep feature folder names identical across the stage folders.

| Requirement group | Rules | Workflow | UI | Acceptance |
|---|---|---|---|---|
| Tenant boundary (REQ-GLT-001..005) | BR-GLT-001..009, 060..066 | WF-01 | | AC-GLT-001..006 |
| Choosing the tenant (010..016) | BR-GLT-003..011 | WF-02 | UI-GLT-012 | AC-GLT-010..018 |
| Connection (020..025) | BR-GLT-012..016 | WF-01, WF-07 | | AC-GLT-020..026 |
| Hierarchy (030..037) | BR-GLT-017..028 | WF-04 | UI-GLT-020..024 | AC-GLT-030..039 |
| Identity (040..046) | BR-GLT-046..049 | WF-02, WF-03 | UI-GLT-001..007 | AC-GLT-040..046 |
| Roles and reach (050..061) | BR-GLT-030..045 | WF-05, WF-06 | UI-GLT-010..014, 030..035 | AC-GLT-050..060 |
| Entitlement (065..069) | BR-GLT-050..054 | WF-07 | UI-GLT section 5 | AC-GLT-017, 018 |
| Audit and jobs (070..076) | BR-GLT-055..057 | WF-07 | | AC-GLT-070..075 |
| Hardening (090..094) | | | | AC-GLT-090..093 |
| Moving existing customers (083) | | WF-08 | | AC-GLT-095 |

## Sources outside this repository

The platform model comes from the EIS, Agile Planner and Macro repositories (`enterprise-intelligence-suite`, `evyoog-thittam-agile`, `evyoog-thittam-macro`); the exact documents are listed at the end of the [reference model](08-architecture/multi-tenancy/platform-reference-model.md). Nothing in this folder has been run against a real identity provider, platform or shared database.
