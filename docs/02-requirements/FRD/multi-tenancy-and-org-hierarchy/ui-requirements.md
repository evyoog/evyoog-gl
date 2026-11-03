# UI requirements: multi-tenancy and organization hierarchy (Finance)

Status: **Draft.** Applies to the Finance frontend, repository `evyoog/evyoog-frontend` (React, Vite, TypeScript, Tailwind v4, axios, react-router-dom, 24 pages). Its current conventions are kept: `ApiResponse` unwrapping, `useAuth`, `hasPermission('code')` for gated actions, the shared `ui` components (`Button`, `Input`, `Select`, `Modal`, `EmptyState`, `ErrorState`, skeletons), toasts, DD-MMM-YYYY dates.
Requirements are in [requirement.md](requirement.md); API in [api-requirements.md](api-requirements.md).

## 1. Sign-in and sign-out

| ID | Requirement |
|---|---|
| UI-GLT-001 | The sign-in page (`LoginPage`) shows no username or password field when SSO is required. It shows one button, "Continue with eVyoog", and tries the platform session once on load. |
| UI-GLT-002 | With no platform session the page redirects once to the platform login (`loginUrl` from `GET /api/auth/sso-config`) and records that it did, so a second arrival does not redirect again. |
| UI-GLT-003 | A person who is signed in at the platform but is not in the group sees a message that says why ("You are signed in at eVyoog but are not a member of a group that has Finance. Ask your administrator.") and a sign-out link. No redirect loop. |
| UI-GLT-004 | Sign-out asks "Sign out of Finance?" and then goes to `{logoutUrl}?redirect=<app>/home`. Other open tabs sign out together. |
| UI-GLT-005 | The app checks the platform session about once a minute and signs out when it has ended; a `401` from any call signs out. |
| UI-GLT-006 | `ChangePasswordPage`, the "must change password" banner on the dashboard, and the reset-password action in `UserManagementPage` are removed in a platform group. |
| UI-GLT-007 | Tokens are not kept in `localStorage`. The access token is held in memory and renewed through `GET /api/auth/session`; the refresh token is not held by the browser. |

## 2. Where am I

| ID | Requirement |
|---|---|
| UI-GLT-010 | The top bar shows the path **Group › Legal Entity › Business Unit (when one is chosen)** and the person's level, taken from `GET /api/v1/me/context`. The current code takes the Legal Entity name from period status and the Legal Entity from the login (`user.legalEntityId`); both are replaced by the context. |
| UI-GLT-011 | A person who can reach more than one Legal Entity sees a Legal Entity selector in the top bar. Choosing one calls `PUT /api/v1/me/active-legal-entity` and reloads the page's data. The selector lists only what is in reach. |
| UI-GLT-012 | A person who belongs to more than one group sees a group selector; the choice is sent as `X-TENANT-ID`. Until the platform shows the group list, the default membership is used. |
| UI-GLT-013 | A header chip shows the source of the session: "From eVyoog platform, via single sign-on". |
| UI-GLT-014 | When the access of the signed-in person changes (what they can see or do differs from when the page opened), a banner offers to reload, as Agile does. A refusal on any call (person switched off, subscription ended) replaces the page with the reason. |

## 3. Screens that use a fixed Business Group or a user-owned list

| ID | Requirement |
|---|---|
| UI-GLT-020 | `EnterpriseStructurePage` and `CoaStructurePage` no longer contain a Business Group id constant. They use the group's Business Group from the context. |
| UI-GLT-021 | `EnterpriseStructurePage`, tab "Legal Entities": in a platform-managed group the add, rename, move and delete actions are not shown. Each Legal Entity card shows a "Managed in eVyoog" label, an "Incomplete" badge when its Finance attributes are missing (with a link to complete them), and keeps the Finance-owned actions: accounting standard, TAN, assign ledger. |
| UI-GLT-022 | `EnterpriseStructurePage`, tab "Business Units": in a platform-managed group no add, rename or delete; GSTIN and state code stay editable; an "Incomplete" badge shows until both are set. |
| UI-GLT-023 | A Business Group needing Thick ES shows a banner "This group has more than one Legal Entity. Switch the Business Group to Thick ES to post on every entity", with the switch for an administrator. |
| UI-GLT-024 | No screen calls `listLedgers()`, `listLegalEntities()` or any list with no scope as a "system-wide picker". Every picker lists only what is in reach. The workaround helpers `getPrimaryLedger*` stay but no longer hide a missing server check. |

## 4. People and roles

| ID | Requirement |
|---|---|
| UI-GLT-030 | `UserManagementPage` in a platform group is a list of the people the platform sent, with level, home unit, status, and Finance functional roles. There is no "Add user" and no password action. A line shows "Managed in eVyoog" for the level and home unit. |
| UI-GLT-031 | The slide-over `UserRolesPanel` assigns **functional roles** (GL Manager, Accountant, Approver, Viewer, Auditor), per Legal Entity, within the assigner's reach. Roles the assigner cannot give are not offered. |
| UI-GLT-032 | `RoleManagementPage` keeps the permission matrix for functional roles. The Admin role is locked; platform levels are listed read-only. |
| UI-GLT-033 | A new screen "Access" (for administrators) shows each person with the Legal Entities and Business Units they reach, grants and their source (Platform or Finance), and lets an administrator give or remove an extra Legal Entity or Business Unit within their own reach. A grant from the platform shows "remove it in eVyoog". |
| UI-GLT-034 | A new screen "My access" (everyone) shows the group, the active Legal Entity, the reached Legal Entities and Business Units, the level, the functional roles and the screens. |
| UI-GLT-035 | A screen the person's level or role does not allow is not in the side menu and its route answers "Your access doesn't include this screen". |

## 5. Messages

| Situation | Text (short, no technical words) |
|---|---|
| `PLATFORM_MANAGED` | "This is managed in eVyoog. Change it there." with a link to the platform |
| `NOT_A_MEMBER` | "You are not a member of this group." |
| `NO_PRODUCT_ACCESS` | "Your administrator has not given you access to Finance." |
| `SUBSCRIPTION_ENDED` | "The Finance subscription of this group has ended." |
| `TENANT_UNAVAILABLE` (suspended) | "Finance is paused for this group." |
| `NOT_PROVISIONED` / `TENANT_NOT_READY` | "Finance is being set up for this group. Try again shortly." |
| `REGISTRY_UNAVAILABLE`, `SCOPE_UNAVAILABLE` | "Finance cannot check your access right now. Try again shortly." |
| Legal Entity incomplete | "Finish setting up this Legal Entity (accounting standard, ledger) before posting." |
| Business Unit incomplete | "Add the state and GSTIN of this Business Unit." |

## 6. Not changed

The journal entry, trial balance, P&L, balance sheet, cash flow, account ledger, period management, dimension, COA, calendar, AIE import and opening balance screens keep their layouts and rules. They gain the context (active Legal Entity from the selector instead of from the login) and the new refusal messages.
