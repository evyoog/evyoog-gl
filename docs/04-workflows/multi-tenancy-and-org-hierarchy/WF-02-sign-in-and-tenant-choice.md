# WF-02: Sign-in and tenant choice

Requirements: REQ-GLT-010..016, 040..045, 065. Rules: BR-GLT-003..008, 011, 046..049.
Platform side **(as built elsewhere)**: EIS login, `vyoog_sso` cookie, `/internal/sso/token`. Finance side: new (today Finance has its own password login).

## A. Opening the application

```mermaid
sequenceDiagram
  autonumber
  participant B as Browser (Finance UI)
  participant F as Finance backend
  participant E as EIS platform

  B->>F: GET /api/auth/sso-config
  F-->>B: enabled, required, loginUrl, logoutUrl
  B->>F: GET /api/auth/session (cookie vyoog_sso)
  alt platform session exists
    F->>E: POST /internal/sso/token (X-Internal-Sso-Secret, ssoSessionId, targetClientId)
    E-->>F: access token for Finance's own client
    F-->>B: access_token, expires_in, sso true
    B->>F: API calls with the token (WF-02 B)
  else no platform session
    F-->>B: 401
    B->>B: redirect once to loginUrl (marker kept)
    B->>E: sign in (MFA, federation rules of the platform)
    E-->>B: back to Finance
  end
  Note over B: a second return without a session shows the reason, not another redirect
```

| # | Step | Rule |
|---|---|---|
| 1 | The page asks `sso-config`. With `required: true` it shows no password form | BR-GLT-046 |
| 2 | It asks `GET /api/auth/session` once. Finance redeems the platform session backend to backend and never returns the cookie value | BR-GLT-047 |
| 3 | No session: one redirect to the platform login. The platform sends the person back only if the address's origin equals a catalog launch origin | BR-GLT-048 |
| 4 | Back without a usable session (for example a person with no membership): a message, no redirect | UI-GLT-003 |
| 5 | The token is for Finance's own identity client. Finance holds no password and no refresh token of its own | BR-GLT-046 |

## B. Every request: choosing the tenant and the gate

```mermaid
flowchart TD
  A[Request with bearer token] --> B[Verify token with the identity provider]
  B --> C{Person or service?}
  C -->|person| D[Find memberships by subject; by e-mail only if verified]
  C -->|service| E[Find grants of the client azp]
  D --> F{How many groups?}
  E --> F
  F -->|none| X1[403 NOT_A_MEMBER or NO_GRANT]
  F -->|one| G[Use it]
  F -->|several| H{X-TENANT-ID among the caller's own?}
  H -->|yes| G
  H -->|names a foreign group| X1
  H -->|no header| I{default membership?}
  I -->|yes| G
  I -->|no| X2[403 GROUP_CHOICE_REQUIRED]
  G --> J{Registry readable?}
  J -->|no| X3[503 REGISTRY_UNAVAILABLE]
  J -->|yes| K{Group active? member active?}
  K -->|no| X4[403 ORG_INACTIVE or USER_INACTIVE]
  K -->|yes| L{Subscription active or trial, not ended?}
  L -->|no| X5[403 NO_SUBSCRIPTION, NOT_ACTIVE or ENDED]
  L -->|yes| M{Member has Finance access?}
  M -->|no| X6[403 NO_PRODUCT_ACCESS]
  M -->|yes| N{Tenant READY?}
  N -->|no| X7[503 NOT_PROVISIONED or TENANT_NOT_READY; suspended 403]
  N -->|yes| O[Bind connection and schema from the registry]
  O --> P[Compute reach: WF-05]
  P --> Q[Controller with permission check]
```

| # | Rule |
|---|---|
| 1 | The tenant never comes from the address, the host, the `Origin`, or a token claim that names a group (BR-GLT-003). |
| 2 | `X-TENANT-ID` chooses only among the caller's own groups. A foreign group answers like an unknown one (BR-GLT-004). |
| 3 | A person is matched by subject. E-mail only if the token says it is verified; the subject is stored on first match (BR-GLT-005). |
| 4 | The registry is read fail closed. Answers are cached at most 60 seconds, so a revoked access takes effect within a minute (BR-GLT-007). |
| 5 | Connection and schema come from the registry only, never from the request (BR-GLT-009). |
| 6 | Sign-in endpoints, branding, health and the webhook do not run the gate. |

## C. A person in several groups

The UI shows a group selector when `GET /api/v1/me/context` lists more than one group. The choice is sent as `X-TENANT-ID`. A person with no default and no choice gets `GROUP_CHOICE_REQUIRED` and the selector opens.

## D. What changes for Finance's existing people

Existing `auth.users` rows in a platform group are replaced by memberships. A person is created by the platform, never by "Add user" in Finance. Own-login, refresh, change-password and reset-password endpoints answer `403 platformSignIn` in a platform group (REQ-GLT-044).
