# OWASP ASVS 5.0 Level 2 self-assessment

r7's evidence for the claim *self-assessed against OWASP ASVS 5.0.0, Level 2*. Every Level 1 and Level 2 requirement is listed: 253 of them. Each row names the control that meets it, or why it does not apply, or the numbered gap that will close it.

This is a working document, not yet a claim. It becomes one when no row is **Gap** or **Partial** without a recorded decision, and someone outside the project has reviewed the evidence. Keep it current: a change that removes a control, or adds a feature that brings a chapter into scope (a JWT filter brings in V9, for example), updates the rows it touches in the same PR.

## Scope and assumptions

- **r7 is the edge of an application, not the application.** Requirements about user accounts, sessions, business logic and payload content belong to the upstream services and are marked N/A here. What r7 contributes is routing, request hygiene, access control at route level, limits, and the audit journal.
- **The data-plane listener is plaintext by design.** r7 runs on a private network or behind a TLS-terminating load balancer, and still expects hostile traffic on that network. TLS requirements for the listener are Accepted or Operator, never Met.
- **The management port is a private control plane.** It binds to 127.0.0.1 unless deliberately exposed.
- **Operators write the routes.** Where r7 provides a control but the configuration decides whether it is used (CORS, rate limits, response headers), the row is **Operator**, and the evidence names the filter.

## Status legend

| Status | Meaning |
|---|---|
| Met | r7 satisfies it; the evidence names the code, PR or test. |
| Partial | Satisfied in part; the rest is a numbered gap. |
| Gap | Not satisfied yet; a numbered gap below tracks the fix. |
| Accepted | Deliberately not satisfied, with the reason recorded. |
| Operator | r7 provides the means; whether it holds depends on deployment or configuration. |
| N/A | r7 has no such feature; where one exists, it belongs to the upstream. |

## Summary

| Chapter | Met | Partial | Gap | Accepted | Operator | N/A |
|---|---:|---:|---:|---:|---:|---:|
| V1 Encoding and Sanitization | 13 | 2 |  |  |  | 12 |
| V2 Validation and Business Logic | 5 | 1 |  |  |  | 5 |
| V3 Web Frontend Security | 5 | 8 |  |  | 5 | 1 |
| V4 API and Web Service | 2 | 1 |  | 1 | 1 | 5 |
| V5 File Handling | 3 |  |  |  |  | 6 |
| V6 Authentication | 4 | 2 |  | 1 |  | 28 |
| V7 Session Management |  |  |  |  |  | 18 |
| V8 Authorization | 3 |  |  |  |  | 4 |
| V9 Self-contained Tokens |  |  |  |  |  | 7 |
| V10 OAuth and OIDC |  |  |  |  |  | 29 |
| V11 Cryptography | 8 |  |  |  |  | 6 |
| V12 Secure Communication | 1 |  |  | 2 | 5 | 1 |
| V13 Configuration | 9 | 1 | 1 |  | 2 |  |
| V14 Data Protection | 4 | 3 |  | 1 |  | 1 |
| V15 Secure Coding and Architecture | 9 | 3 | 1 |  |  |  |
| V16 Security Logging and Error Handling | 8 | 5 | 2 |  | 1 |  |
| V17 WebRTC |  |  |  |  |  | 7 |
| **Total** | **74** | **26** | **4** | **5** | **14** | **130** |

## Open gaps

Numbered so rows can refer to them. Most are small.

1. **Refuse TRACE by default** (V13.4.4). Answer TRACE with 405 before routing, with a documented opt-out for a route that genuinely needs it.
2. **Harden r7's own responses** (V3.2.1, V3.4.4, V4.1.1). Add `X-Content-Type-Options: nosniff` to every response r7 generates itself (400, 404, 500, 503, short-circuits), and add `charset=utf-8` to `text/plain`.
3. **CSP on the dashboard** (V3.4.3, V3.4.6). Send `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'` from the management port. [#55](https://github.com/ethlo/r7/pull/55) already removed the inline handlers.
4. **Log timestamps with date and zone** (V16.2.2). Change `default-logback.xml` to ISO-8601 in UTC (`%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX, UTC}`).
5. **Cookie defaults** (V3.3.1, V3.3.2, V3.3.4). Document that `SetResponseCookie` needs `secure: true` behind TLS, and consider defaulting `http_only: true` and `same_site: Lax`. Changing the defaults breaks existing configs silently, so decide explicitly.
6. **Brute-force guidance for BasicAuth** (V6.1.1, V6.3.1). Document that `BasicAuth` has no per-user lockout and that its routes should also carry a `RateLimiter`. Optionally warn at load when they do not.
7. **Security documentation** (V2.1.3, V13.1.1, V14.1.x, V15.1.1, V15.1.3, V16.1.1). `SECURITY.md` (disclosure contact, supported versions, remediation time frames) and a `docs/security.md` covering data classification, every connection r7 makes, the log inventory, resource-demanding features and their bounds, and every limit.
8. **Repeated query parameters** (V15.3.7). Specify and test how `RequireMatchQueryParameter` and `MatchQueryParameter` treat a repeated parameter (all values or any), so `?role=user&role=admin` cannot satisfy a check the upstream then reads differently.
9. **Security event log** (V16.3.1–V16.3.3). A dedicated `r7.security` logger at INFO with one structured line per authentication failure, authorisation refusal, rate-limit refusal and guard rejection (request ID, client IP, route, reason), rate-limited itself so a flood cannot fill the disk.
10. **Generic 503 body** (V16.5.1). Drop the route ID from the no-upstream 503 body; keep it in the log and the journal.

Also recorded:

- **Accepted:** query strings are journaled unredacted (V14.2.1). This was a deliberate choice during the security review; revisit it if the journals leave the host.
- **Accepted:** the listener is plaintext and `BasicAuth` is single-factor (V12.2.1, V12.3.1, V4.4.1, V6.3.3); see Scope.
- **Roadmap:** Jazzer fuzzing of the journal decoder and the request guards moves V1.4.1 and V1.4.2 to Met. A per-image SBOM moves V15.1.2 to Met.

## V1 Encoding and Sanitization

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V1.1.1 | 2 | Decode input to canonical form once, before validation | Met | Undertow decodes the path once; `RequestPathGuard` then refuses anything a second decode or the upstream could read differently (dot-segments, `%2e`/`%2f`/`%5c`/`%25` left after decoding, backslash, control characters) with 400, before routing ([#39](https://github.com/ethlo/r7/pull/39), `RequestPathGuardTest`). |
| V1.1.2 | 2 | Encode output as the final step, for its interpreter | Met | Rewritten paths are re-encoded by `PathEncoder` as they are handed to the proxy ([#40](https://github.com/ethlo/r7/pull/40), `PathEncoderTest`); header values are validated at the point of mutation (`TextValuesTest`); the dashboard escapes at render time ([#55](https://github.com/ethlo/r7/pull/55)). |
| V1.2.1 | 1 | Context-aware output encoding (HTML, headers) | Met | Header mutation rejects CR/LF and non-ISO-8859-1 (`UndertowGatewayHeadersValidationTest`); the dashboard escapes every config-derived value ([#55](https://github.com/ethlo/r7/pull/55)). |
| V1.2.2 | 1 | Encode untrusted data in built URLs; safe URL schemes only | Met | `PathEncoder` ([#40](https://github.com/ethlo/r7/pull/40)); `TemplateRedirect` refuses computed locations that leave the template's origin, change scheme or start with `//` ([#49](https://github.com/ethlo/r7/pull/49), `TemplateRedirectFactoryTest`). |
| V1.2.3 | 1 | Encode when building JavaScript/JSON | Met | Management JSON is produced by Jackson; the dashboard builds DOM from escaped values and delegated listeners, not inline handlers ([#55](https://github.com/ethlo/r7/pull/55)). The tailers serialise with Jackson. |
| V1.2.4 | 1 | Parameterised database queries | N/A | r7 has no database. The ClickHouse tailer writes `JSONEachRow` lines with a JSON generator; no SQL is built. |
| V1.2.5 | 1 | OS command injection | N/A | r7 never starts processes. |
| V1.2.6 | 2 | LDAP injection | N/A | No LDAP. |
| V1.2.7 | 2 | XPath injection | N/A | No XPath. |
| V1.2.8 | 2 | LaTeX injection | N/A | No LaTeX. |
| V1.2.9 | 2 | Escape regex metacharacters in untrusted input | Met | Patterns come only from configuration, never from request data. Matching untrusted input against them runs under `RegexBudget` ([#53](https://github.com/ethlo/r7/pull/53)). |
| V1.3.1 | 1 | Sanitise WYSIWYG HTML | N/A | r7 accepts no HTML content. |
| V1.3.2 | 1 | No eval or dynamic code execution | Met | No scripting engines or expression languages. Filters and predicates are compiled classes registered through `@AutoService`. |
| V1.3.3 | 2 | Sanitise data before dangerous contexts | Met | Header and start-line text is validated as ISO-8859-1 without CR/LF when set (`TextValuesTest`); paths are re-encoded ([#40](https://github.com/ethlo/r7/pull/40)). |
| V1.3.4 | 2 | Sanitise user-supplied SVG | N/A | r7 accepts no uploads to serve back. |
| V1.3.5 | 2 | Sanitise template/markup languages | N/A | No user-supplied markup is rendered. |
| V1.3.6 | 2 | SSRF protection | Met | Upstream targets come only from configuration. Absolute-form requests naming another host are refused (400). `X-Forwarded-For`/`X-Real-IP` are parsed as literals and never resolved (`RemoteAddressResolverTest`). |
| V1.3.7 | 2 | Template injection | Met | `TemplateRedirect` templates are configuration; request data fills placeholders and cannot introduce template syntax ([#49](https://github.com/ethlo/r7/pull/49)). |
| V1.3.8 | 2 | JNDI injection | N/A | No JNDI lookups. Logback is configured from a bundled file. |
| V1.3.9 | 2 | Memcache injection | N/A | No memcache. |
| V1.3.10 | 2 | Format string safety | Met | Logging uses SLF4J `{}` placeholders; no request data reaches a format string. |
| V1.3.11 | 2 | SMTP/IMAP injection | N/A | No mail. |
| V1.4.1 | 2 | Memory-safe buffers | Partial | Java bounds-checks heap access, but the journal uses `MappedByteBuffer` and off-heap reads, and the decoder parses untrusted bytes on recovery. Bounds are enforced by the format (`FORMAT.md`) and `JournalIntegrityTest`. Fuzzing the decoder and recovery with Jazzer (roadmap phase 3) closes this. |
| V1.4.2 | 2 | Prevent integer overflow | Partial | Sizes and counts are `long`; config bounds are validated (`LimitsConfigValidationTest`, `StorageConfigValidationTest`). Length fields read from journal segments need the same fuzzing as V1.4.1. |
| V1.4.3 | 2 | Release resources, no dangling references | Met | Segments are unmapped and deleted only with proof (`design/journal-invariants.md`, `JournalLifecycleTest`); upstream connections are closed on abort ([#44](https://github.com/ethlo/r7/pull/44)). |
| V1.5.1 | 1 | XML parsers without external entities | N/A | r7 parses no XML. YAML is read by Jackson, which has no entity expansion. |
| V1.5.2 | 2 | Safe deserialisation | Met | Jackson maps config onto fixed records with `FAIL_ON_UNKNOWN_PROPERTIES` and no default typing. No Java serialisation. Journals are FlatBuffers with a fixed schema. |

## V2 Validation and Business Logic

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V2.1.1 | 1 | Documented input validation rules | Met | `docs/config.md` specifies what the request path guard, `TransferEncodingGuard` and listener limits accept, and each `Require*`/`Match*` filter's semantics. |
| V2.1.2 | 2 | Documented cross-field consistency rules | N/A | r7 does not interpret business data. |
| V2.1.3 | 2 | Documented business logic limits | Partial | Rate limits, size limits and the regex budget are documented per filter. There is no single page listing every limit; `docs/security.md` (to come) should collect them. |
| V2.2.1 | 1 | Positive input validation | Met | Request framing, path and header text are validated against allowlists ([#39](https://github.com/ethlo/r7/pull/39), [#45](https://github.com/ethlo/r7/pull/45)); config is validated fail-fast with field names. |
| V2.2.2 | 1 | Validation at a trusted service layer | Met | All checks run in the gateway. |
| V2.2.3 | 2 | Combinations of related data items are reasonable | N/A | No business data. |
| V2.3.1 | 1 | Business flows in sequence | N/A | No business flows. |
| V2.3.2 | 2 | Business logic limits implemented | Met | Per-route `RateLimiter` and `RequestSizeLimit` enforce what the config declares (`RateLimiterFactoryTest`, `RequestSizeLimitStreamingTest`). |
| V2.3.3 | 2 | Transactions | N/A | No business transactions. |
| V2.3.4 | 2 | Locking for limited resources | N/A | No bookable resources. |
| V2.4.1 | 2 | Anti-automation controls | Met | `RateLimiter` per client address, keyed by IPv6 /64 by default ([#52](https://github.com/ethlo/r7/pull/52)); bcrypt concurrency is bounded so authentication cannot exhaust the CPU ([#47](https://github.com/ethlo/r7/pull/47)). Operators choose which routes to limit. |

## V3 Web Frontend Security

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V3.2.1 | 1 | Prevent rendering in the wrong context | Partial | The management port sends `nosniff`, `X-Frame-Options: DENY` and `no-store` ([#46](https://github.com/ethlo/r7/pull/46)). r7's own data-plane responses (400/404/500/503) carry `text/plain` but no `nosniff` (gap 2). Proxied responses are the upstream's, and operators can add headers with `SetResponseHeader`. |
| V3.2.2 | 1 | Render text with safe DOM functions | Met | Dashboard values are escaped and navigation uses `data-` attributes, not inline script ([#55](https://github.com/ethlo/r7/pull/55)). |
| V3.3.1 | 1 | Cookies Secure, with a `__Host-`/`__Secure-` prefix | Partial | `SetResponseCookie` only adds `Secure` when configured (default off). With no TLS on the listener, `Secure` is right only behind a TLS terminator. Gap 5: document the requirement and warn when it is off. |
| V3.3.2 | 2 | SameSite set per cookie purpose | Partial | `SetResponseCookie` validates `same_site` but sets none by default (gap 5). |
| V3.3.3 | 2 | `__Host-` prefix | Operator | The cookie name is the operator's. |
| V3.3.4 | 2 | HttpOnly for cookies scripts must not read | Partial | `http_only` defaults to off (gap 5). |
| V3.4.1 | 1 | HSTS | Operator | Needs TLS, which r7's listener does not terminate. Set it at the TLS terminator, or with `SetResponseHeader` behind one. |
| V3.4.2 | 1 | CORS allow-origin fixed or allowlisted | Met | `Cors` echoes only allowlisted origins, adds `Vary: Origin`, answers only real preflights and overrules upstream grants ([#51](https://github.com/ethlo/r7/pull/51), `CorsFactoryTest`, `CorsE2ETest`). |
| V3.4.3 | 2 | Content-Security-Policy | Partial | Proxied responses: the upstream's, or the operator's through `SetResponseHeader`. Dashboard: no CSP yet, although [#55](https://github.com/ethlo/r7/pull/55) removed its inline handlers; gap 3. |
| V3.4.4 | 2 | `X-Content-Type-Options: nosniff` on all responses | Partial | Management yes ([#46](https://github.com/ethlo/r7/pull/46)); r7's data-plane error responses no (gap 2). |
| V3.4.5 | 2 | Referrer-Policy | Partial | Management `no-referrer` ([#46](https://github.com/ethlo/r7/pull/46)); data plane is the operator's through `SetResponseHeader`. |
| V3.4.6 | 2 | CSP `frame-ancestors` | Partial | Management sends `X-Frame-Options: DENY` but no CSP (gap 3). |
| V3.5.1 | 1 | Cross-origin requests validated without preflight | Operator | The upstream's CSRF defence. r7 can require an origin header with `RequireMatchRequestHeader`. |
| V3.5.2 | 1 | Sensitive calls cannot avoid preflight | Operator | As V3.5.1. |
| V3.5.3 | 1 | Sensitive functions do not use safe methods | Met | The management port accepts only GET/HEAD and changes no state ([#46](https://github.com/ethlo/r7/pull/46)). Data-plane semantics are the upstream's. |
| V3.5.4 | 2 | Separate applications on separate hostnames | Operator | The `Host` predicate supports it; the deployment decides. |
| V3.5.5 | 2 | postMessage origin checks | N/A | The dashboard uses no postMessage. |
| V3.7.1 | 2 | Supported client-side technologies only | Met | The dashboard is plain HTML and JavaScript. |
| V3.7.2 | 2 | Redirects to other domains only from an allowlist | Met | `TemplateRedirect` refuses locations that leave the template's origin ([#49](https://github.com/ethlo/r7/pull/49)). Upstream `Location` headers pass through unchanged, as the upstream's decision. |

## V4 API and Web Service

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V4.1.1 | 1 | Content-Type with charset on every body | Partial | r7's own bodies are `text/plain` with no charset (gap 2); management JSON is `application/json`. |
| V4.1.2 | 2 | HTTP-to-HTTPS redirects only on user-facing endpoints | N/A | r7 does not redirect to HTTPS. TLS is in front of it. |
| V4.1.3 | 2 | Intermediary-set headers cannot be overridden by clients | Met | `X-Forwarded-*`, `Forwarded` and `X-Real-IP` are stripped from untrusted peers before proxying ([#43](https://github.com/ethlo/r7/pull/43), `UpstreamHeaderSanitizerTest`); the client address is taken only from `trusted_proxies` (`RemoteAddressResolverTest`). `BasicAuth` removes the consumed `Authorization` ([#47](https://github.com/ethlo/r7/pull/47)). |
| V4.2.1 | 2 | Message boundaries (request smuggling) | Met | Non-canonical `Transfer-Encoding` refused with 400 and a closed connection ([#45](https://github.com/ethlo/r7/pull/45), `TransferEncodingGuardTest`); CL+TE, duplicate CL and bare LF rejected (probed live during the security review); hop-by-hop headers stripped ([#43](https://github.com/ethlo/r7/pull/43)); h2c off by default ([#56](https://github.com/ethlo/r7/pull/56), `Http2DefaultTest`); truncated chunked bodies never reach the upstream as complete ([#44](https://github.com/ethlo/r7/pull/44), `RequestSizeLimitStreamingTest`). A differential test against nginx/Node is in roadmap phase 3. |
| V4.3.1 | 2 | GraphQL cost limits | N/A | No GraphQL. |
| V4.3.2 | 2 | GraphQL introspection | N/A | No GraphQL. |
| V4.4.1 | 1 | WSS only | Accepted | The listener is plaintext by design (see Scope). WebSocket upgrades are proxied as-is. |
| V4.4.2 | 2 | WebSocket Origin checked | Operator | No dedicated control. `RequireMatchRequestHeader` on `Origin` enforces an allowlist on the upgrade route. |
| V4.4.3 | 2 | Dedicated WebSocket tokens | N/A | r7 has no sessions; the upstream's. |
| V4.4.4 | 2 | WebSocket tokens from the HTTPS session | N/A | As V4.4.3. |

## V5 File Handling

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V5.1.1 | 2 | Documented upload types and sizes | N/A | r7 stores no uploads. Proxied bodies are bounded by `max_entity_size` and `RequestSizeLimit`. |
| V5.2.1 | 1 | Only file sizes that can be processed | Met | `max_entity_size` and `RequestSizeLimit` hold for chunked bodies too ([#44](https://github.com/ethlo/r7/pull/44)). |
| V5.2.2 | 1 | File extension and content validated | N/A | No uploads. |
| V5.2.3 | 2 | Archive size and count limits | N/A | r7 does not decompress. |
| V5.3.1 | 1 | Uploaded files never executed | Met | `StaticContent` serves files as bytes; nothing is executed. |
| V5.3.2 | 1 | File paths from trusted data | Met | `StaticContent` resolves under a configured base, refusing traversal and symlink escape by default and dotfiles unless enabled ([#50](https://github.com/ethlo/r7/pull/50), `StaticContentHiddenFilesTest`); journal file names are generated. |
| V5.4.1 | 2 | Ignore user filenames, set Content-Disposition | N/A | No downloads generated from user filenames. |
| V5.4.2 | 2 | Served filenames encoded | N/A | As V5.4.1. |
| V5.4.3 | 2 | Antivirus scanning of untrusted files | N/A | r7 does not accept files to serve. |

## V6 Authentication

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V6.1.1 | 1 | Documented anti-brute-force controls | Partial | `docs/config.md` documents `BasicAuth`, bounded bcrypt concurrency (503 when saturated, [#47](https://github.com/ethlo/r7/pull/47)) and `RateLimiter`. It does not yet say that `BasicAuth` has no per-user lockout, or that routes using it should also carry a `RateLimiter`; gap 6. |
| V6.1.2 | 2 | Context-specific password blocklist | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.1.3 | 2 | All authentication pathways documented | Met | `BasicAuth` is the only authenticator. `RequireAuthorizationHeader` is a presence check and is documented as such. |
| V6.2.1 | 1 | Password length at least 8 | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.2 | 1 | Users can change passwords | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.3 | 1 | Password change needs the current password | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.4 | 1 | Check against top 3000 passwords | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.5 | 1 | No composition rules | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.6 | 1 | Password fields masked | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.7 | 1 | Paste and password managers allowed | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.8 | 1 | Password verified exactly as received | Met | The decoded password is passed to bcrypt unmodified (`BasicAuthFactoryTest`, `BCryptTest`). |
| V6.2.9 | 2 | 64-character passwords allowed | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.10 | 2 | No forced rotation | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.11 | 2 | Context-word blocklist applied | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.2.12 | 2 | Breached-password check | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.3.1 | 1 | Credential stuffing and brute-force controls | Partial | Unknown users take the same time as known ones, and bcrypt concurrency is bounded ([#47](https://github.com/ethlo/r7/pull/47)). No per-user or per-client lockout; attempts are limited only by a `RateLimiter` the operator adds (gap 6). |
| V6.3.2 | 1 | No default accounts | Met | No users exist until configured. |
| V6.3.3 | 2 | MFA for access | Accepted | `BasicAuth` is single-factor, meant for machine clients and internal tools. User-facing authentication with MFA belongs to the upstream or an identity provider in front. |
| V6.3.4 | 2 | No undocumented authentication pathways | Met | As V6.1.3. Fallback routes run the fallback's own filters ([#41](https://github.com/ethlo/r7/pull/41), `BasicAuthFallbackTest`). |
| V6.4.1 | 1 | Initial passwords random and short-lived | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.4.2 | 1 | No password hints | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.4.3 | 2 | Secure password reset | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.4.4 | 2 | MFA recovery with identity proofing | N/A | `BasicAuth` checks configured bcrypt hashes; there is no user store, registration, password change or recovery. Credential policy belongs to whoever generates the hashes. |
| V6.5.1 | 2 | OTPs single use | N/A | r7 has no OTP or out-of-band factors. |
| V6.5.2 | 2 | Lookup secrets hashed | N/A | r7 has no OTP or out-of-band factors. |
| V6.5.3 | 2 | OTP secrets from a CSPRNG | N/A | r7 has no OTP or out-of-band factors. |
| V6.5.4 | 2 | OTP entropy | N/A | r7 has no OTP or out-of-band factors. |
| V6.5.5 | 2 | OTP lifetime | N/A | r7 has no OTP or out-of-band factors. |
| V6.6.1 | 2 | PSTN OTP restrictions | N/A | r7 has no OTP or out-of-band factors. |
| V6.6.2 | 2 | Out-of-band codes bound to the request | N/A | r7 has no OTP or out-of-band factors. |
| V6.6.3 | 2 | Out-of-band code brute force | N/A | r7 has no OTP or out-of-band factors. |
| V6.8.1 | 2 | Identity not spoofable across IdPs | N/A | r7 does not federate identity. |
| V6.8.2 | 2 | Assertion signatures validated | N/A | r7 does not federate identity. |
| V6.8.3 | 2 | SAML assertions used once | N/A | r7 does not federate identity. |
| V6.8.4 | 2 | Authentication strength from the IdP verified | N/A | r7 does not federate identity. |

## V7 Session Management

Not applicable: r7 is stateless: no sessions or session tokens. `BasicAuth` verifies every request.

<details><summary>18 requirements</summary>

| ID | L | Requirement |
|---|---|---|
| V7.1.1 | 2 | Session timeouts documented |
| V7.1.2 | 2 | Concurrent sessions documented |
| V7.1.3 | 2 | Federated session systems documented |
| V7.2.1 | 1 | Session tokens verified by a backend |
| V7.2.2 | 1 | Dynamic session tokens |
| V7.2.3 | 1 | Reference tokens from a CSPRNG |
| V7.2.4 | 1 | New token on authentication |
| V7.3.1 | 2 | Inactivity timeout |
| V7.3.2 | 2 | Absolute session lifetime |
| V7.4.1 | 1 | Termination prevents reuse |
| V7.4.2 | 1 | Sessions end when accounts are disabled |
| V7.4.3 | 2 | Terminate other sessions after factor change |
| V7.4.4 | 2 | Visible logout |
| V7.4.5 | 2 | Admins can terminate sessions |
| V7.5.1 | 2 | Re-authentication before sensitive changes |
| V7.5.2 | 2 | Users can view and end sessions |
| V7.6.1 | 2 | RP/IdP session lifetimes |
| V7.6.2 | 2 | Session creation needs user action |

</details>

## V8 Authorization

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V8.1.1 | 1 | Documented function- and data-level authorisation rules | Met | Route predicates and filters are the function-level rules, declared in `routes.yaml`, with first-match semantics specified in `docs/config.md` §3. Data-level rules are the upstream's. |
| V8.1.2 | 2 | Documented field-level rules | N/A | r7 does not inspect payloads. |
| V8.2.1 | 1 | Function-level access needs explicit permission | Met | A route's `Require*`/`BasicAuth` filters apply to whatever the predicates match; ambiguous paths that could match one route and reach another are refused ([#39](https://github.com/ethlo/r7/pull/39), [#40](https://github.com/ethlo/r7/pull/40)); fallback routes carry the matched route's filters ([#41](https://github.com/ethlo/r7/pull/41), `FallbackValidationTest`, `BasicAuthFallbackTest`); no match is 404. |
| V8.2.2 | 1 | Data-specific access (IDOR) | N/A | The upstream's. |
| V8.2.3 | 2 | Field-level access | N/A | The upstream's. |
| V8.3.1 | 1 | Authorisation at a trusted layer | Met | Enforced in the gateway on the decoded, guarded request, never from client-controlled hints (`X-Forwarded-*` stripped, [#43](https://github.com/ethlo/r7/pull/43)). |
| V8.4.1 | 2 | Cross-tenant controls | N/A | r7 is single-tenant. |

## V9 Self-contained Tokens

Not applicable: r7 has no JWT or other token validation. A token-validating filter would bring this chapter into scope.

<details><summary>7 requirements</summary>

| ID | L | Requirement |
|---|---|---|
| V9.1.1 | 1 | Token signature validated |
| V9.1.2 | 1 | Algorithm allowlist |
| V9.1.3 | 1 | Trusted key sources |
| V9.2.1 | 1 | Validity window checked |
| V9.2.2 | 2 | Token type checked |
| V9.2.3 | 2 | Audience checked |
| V9.2.4 | 2 | Audience restriction per issuer key |

</details>

## V10 OAuth and OIDC

Not applicable: r7 is not an OAuth client, resource server or authorisation server.

<details><summary>29 requirements</summary>

| ID | L | Requirement |
|---|---|---|
| V10.1.1 | 2 | Tokens only to components that need them |
| V10.1.2 | 2 | Values bound to the authorisation flow |
| V10.2.1 | 2 | OAuth client CSRF protection |
| V10.2.2 | 2 | Mix-up defence |
| V10.3.1 | 2 | Resource server audience check |
| V10.3.2 | 2 | Delegated authorisation from claims |
| V10.3.3 | 2 | Unique user from token |
| V10.3.4 | 2 | Authentication strength from token |
| V10.4.1 | 1 | Redirect URI allowlist |
| V10.4.2 | 1 | Authorisation code single use |
| V10.4.3 | 1 | Authorisation code lifetime |
| V10.4.4 | 1 | Only the grants a client needs |
| V10.4.5 | 1 | Refresh token replay |
| V10.4.6 | 2 | PKCE |
| V10.4.7 | 2 | Dynamic client registration |
| V10.4.8 | 2 | Refresh token absolute expiry |
| V10.4.9 | 2 | Token revocation |
| V10.4.10 | 2 | Confidential client authentication |
| V10.4.11 | 2 | Minimal scopes |
| V10.5.1 | 2 | ID token replay |
| V10.5.2 | 2 | User from `sub` |
| V10.5.3 | 2 | AS metadata impersonation |
| V10.5.4 | 2 | ID token audience |
| V10.5.5 | 2 | Back-channel logout |
| V10.6.1 | 2 | OP response modes |
| V10.6.2 | 2 | Forced logout |
| V10.7.1 | 2 | Consent per request |
| V10.7.2 | 2 | Clear consent information |
| V10.7.3 | 2 | Consent review and revocation |

</details>

## V11 Cryptography

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V11.1.1 | 2 | Key management policy | N/A | r7 holds no cryptographic keys: no TLS listener, no signing. bcrypt hashes are verifiers, not keys. |
| V11.1.2 | 2 | Cryptographic inventory | Met | bcrypt (`BasicAuth`), CRC32C (journal integrity, not security), JDK TLS for upstream connections, SHA-256 (`bin/flatc.sha256`). This list is the inventory. |
| V11.2.1 | 2 | Industry-validated implementations | Met | The JDK's TLS stack and a bcrypt library; nothing hand-rolled. |
| V11.2.2 | 2 | Crypto agility | Met | The bcrypt cost comes from each hash; upstream TLS follows JDK security properties. |
| V11.2.3 | 2 | At least 128-bit security | Met | JDK default TLS suites. bcrypt is outside this metric. |
| V11.3.1 | 1 | No ECB or weak padding | N/A | r7 encrypts nothing itself. |
| V11.3.2 | 1 | Approved ciphers and modes | Met | Upstream TLS uses the JDK defaults. |
| V11.3.3 | 2 | Authenticated encryption | N/A | No application-level encryption. |
| V11.4.1 | 1 | Approved hash functions | Met | SHA-256 for binary verification. CRC32C detects corruption and is not a security control. |
| V11.4.2 | 2 | Password hashing KDF | Met | bcrypt, with cost validated at load (`BCryptTest`, `BasicAuthFactoryTest`). |
| V11.4.3 | 2 | Collision-resistant integrity hashes | Met | SHA-256 ([#60](https://github.com/ethlo/r7/pull/60)). Journal integrity against tampering is out of scope: file permissions protect journals ([#48](https://github.com/ethlo/r7/pull/48)). |
| V11.4.4 | 2 | KDF for keys derived from passwords | N/A | No keys derived from passwords. |
| V11.5.1 | 2 | CSPRNG, 128 bits for unguessable values | N/A | r7 generates no secrets. Request IDs are correlation identifiers, not capabilities. |
| V11.6.1 | 2 | Approved key generation and signatures | N/A | No keys or signatures. |

## V12 Secure Communication

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V12.1.1 | 1 | Only TLS 1.2 and 1.3 | Operator | The listener is plaintext by design: r7 is deployed on private networks or behind a TLS-terminating load balancer (see Scope). Upstream TLS uses the JDK defaults (1.2/1.3). |
| V12.1.2 | 2 | Recommended cipher suites | Operator | The listener is plaintext by design: r7 is deployed on private networks or behind a TLS-terminating load balancer (see Scope). |
| V12.1.3 | 2 | mTLS client certificates validated | N/A | No mTLS. |
| V12.2.1 | 1 | TLS for external-facing services | Accepted | The listener is plaintext by design: r7 is deployed on private networks or behind a TLS-terminating load balancer (see Scope). |
| V12.2.2 | 1 | Publicly trusted certificates | Operator | The listener is plaintext by design: r7 is deployed on private networks or behind a TLS-terminating load balancer (see Scope). |
| V12.3.1 | 2 | Encryption for all inbound and outbound connections | Accepted | The listener is plaintext by design: r7 is deployed on private networks or behind a TLS-terminating load balancer (see Scope). Upstream connections use TLS when the target URL is `https`. The management port binds to 127.0.0.1 by default ([#46](https://github.com/ethlo/r7/pull/46)). |
| V12.3.2 | 2 | TLS clients validate certificates | Met | Undertow verifies upstream certificates and hostnames by default, and r7 does not turn this off. |
| V12.3.3 | 2 | TLS between internal services | Operator | Per upstream, by using `https` target URLs. |
| V12.3.4 | 2 | Trusted certificates between internal services | Operator | The JVM trust store decides. |

## V13 Configuration

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V13.1.1 | 2 | Communication needs documented | Partial | Upstreams are listed in `routes.yaml` and the ports in `server.yaml`/`docs/config.md`; no single page yet names every connection (data plane, management, upstreams, tailers' sinks). Gap 7. |
| V13.2.1 | 2 | Backend communication authenticated | Operator | `InjectBasicAuth` and header filters authenticate r7 to upstreams; the deployment chooses. |
| V13.2.2 | 2 | Least-privilege accounts | Met | Images run as UID 65532 on distroless bases; journals are 0640 in a 0750 directory ([#48](https://github.com/ethlo/r7/pull/48)). |
| V13.2.3 | 2 | No default credentials | Met | No shipped credentials. |
| V13.2.4 | 2 | Allowlist of external resources | Met | Upstream targets are an explicit list in configuration; requests cannot choose one (V1.3.6). |
| V13.2.5 | 2 | Server-side allowlist for outbound requests | Met | As V13.2.4. |
| V13.3.1 | 2 | Secrets management solution | Operator | `${VAR}` interpolation lets secrets come from the environment or a mounted secret rather than the YAML; the vault is the deployment's. |
| V13.3.2 | 2 | Least privilege for secrets | Met | Values marked `@Sensitive` are masked in management output ([#46](https://github.com/ethlo/r7/pull/46)); URL credentials are redacted in summaries. |
| V13.4.1 | 1 | No source control metadata served | Met | `StaticContent` refuses dotfiles, `.git/` included, unless `serve_hidden_files` is set ([#50](https://github.com/ethlo/r7/pull/50)). |
| V13.4.2 | 2 | Debug modes off | Met | No debug endpoints. Log levels default to INFO/WARN (`default-logback.xml`). |
| V13.4.3 | 2 | No directory listings | Met | `list_directory` defaults to false. |
| V13.4.4 | 2 | TRACE not supported | Gap | r7 forwards any method, TRACE included, and does not refuse it by default (gap 1). |
| V13.4.5 | 2 | Documentation and monitoring endpoints not exposed | Met | The management port binds to 127.0.0.1 by default ([#46](https://github.com/ethlo/r7/pull/46), `ManagementConfigTest`); the Docker images override that deliberately with `R7_MANAGEMENT_HOST`. |

## V14 Data Protection

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V14.1.1 | 2 | Sensitive data identified and classified | Partial | In code: journal header allowlist (`RedactingHeadersTest`), `@Sensitive` config ([#46](https://github.com/ethlo/r7/pull/46)). Not written down as a classification (gap 7). |
| V14.1.2 | 2 | Protection requirements per level | Partial | As V14.1.1. |
| V14.2.1 | 1 | No sensitive data in URLs | Accepted | r7 cannot stop clients putting secrets in query strings. It journals the start line unredacted, a deliberate decision recorded against F10. Operators can lower the journal level, or strip parameters with `RemoveQueryParameter`. |
| V14.2.2 | 2 | Sensitive data not cached in server components | Met | r7 has no response cache; bodies are streamed. Journals are the only copy, with permissions ([#48](https://github.com/ethlo/r7/pull/48)) and retention (reaper). |
| V14.2.3 | 2 | No sensitive data to untrusted parties | Met | r7 sends data only to configured upstreams and local journals; the dashboard loads no third-party resources. |
| V14.2.4 | 2 | Controls for sensitive data (retention, logging) | Partial | Journal levels per route and direction, header allowlist, reaper retention. Query values are the recorded exception (V14.2.1). |
| V14.3.1 | 1 | Client storage cleared on logout | N/A | No sessions. |
| V14.3.2 | 2 | Anti-caching headers for sensitive data | Met | The management port sends `Cache-Control: no-store` ([#46](https://github.com/ethlo/r7/pull/46)). `RemoveCacheHeaders`/`SetResponseHeader` are available for data-plane routes. |
| V14.3.3 | 2 | No sensitive data in browser storage | Met | The dashboard stores nothing. |

## V15 Secure Coding and Architecture

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V15.1.1 | 1 | Documented remediation time frames | Gap | No policy yet; `SECURITY.md` (gap 7). |
| V15.1.2 | 2 | SBOM and trusted component sources | Partial | A CycloneDX SBOM is built on every CI run and scanned by OSV-Scanner ([#59](https://github.com/ethlo/r7/pull/59)); dependencies come from Maven Central, base images are pinned by digest ([#72](https://github.com/ethlo/r7/pull/72)), and `flatc` is verified by checksum ([#60](https://github.com/ethlo/r7/pull/60)). The SBOM is not yet published with each image (deferred). |
| V15.1.3 | 2 | Documented resource-demanding functionality | Partial | Known expensive paths: bcrypt, regex matching, FULL body journaling, static content. Each is bounded in code ([#47](https://github.com/ethlo/r7/pull/47), [#53](https://github.com/ethlo/r7/pull/53), backpressure), but they are not listed in one place (gap 7). |
| V15.2.1 | 1 | No components past remediation time frames | Met | OSV-Scanner fails CI on any advisory not accepted with an expiry in `osv-scanner.toml` ([#59](https://github.com/ethlo/r7/pull/59)), and Dependabot proposes updates weekly ([#58](https://github.com/ethlo/r7/pull/58)). Becomes fully Met once V15.1.1 exists. |
| V15.2.2 | 2 | Defences against resource exhaustion | Met | Regex budget ([#53](https://github.com/ethlo/r7/pull/53)), bcrypt semaphore ([#47](https://github.com/ethlo/r7/pull/47)), listener limits (header size and count, parse timeout, entity size), `RateLimiter`, `CircuitBreaker`, journal backpressure. |
| V15.2.3 | 2 | No extraneous functionality in production | Met | Distroless images hold only the jar or native binary; test code is not packaged. |
| V15.3.1 | 1 | Only the required fields returned | Met | Management summaries mask `@Sensitive` values and fingerprint secrets ([#46](https://github.com/ethlo/r7/pull/46)). |
| V15.3.2 | 2 | Outbound calls do not follow redirects | Met | The proxy passes upstream 3xx responses to the client and never follows them. |
| V15.3.3 | 2 | Mass assignment | Met | Config deserialises with `FAIL_ON_UNKNOWN_PROPERTIES` onto records. |
| V15.3.4 | 2 | Original client IP transferred through trusted fields | Met | `RemoteAddressResolver` believes forwarding headers only from `trusted_proxies`, right to left, and fails closed (`RemoteAddressResolverTest`); untrusted peers' forwarding headers are stripped before proxying ([#43](https://github.com/ethlo/r7/pull/43)). |
| V15.3.5 | 2 | Strict types and comparisons | Met | Java, with typed config records. |
| V15.3.6 | 2 | No prototype pollution | Met | The dashboard does not merge untrusted objects. |
| V15.3.7 | 2 | HTTP parameter pollution | Partial | Duplicate `Authorization` values are refused ([#47](https://github.com/ethlo/r7/pull/47)). `RequireMatchQueryParameter`/`MatchQueryParameter` with repeated parameters should be specified and tested: does every value have to match, or any? (gap 8). |

## V16 Security Logging and Error Handling

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V16.1.1 | 2 | Logging inventory | Partial | `docs/journaling.md` covers journals; application logs (logback) are not inventoried (gap 7). |
| V16.2.1 | 2 | Who/what/when/where metadata | Met | Every journaled exchange records request ID, client IP and its source, start line, status and timings (`journal.fbs` `EndExchange`). |
| V16.2.2 | 2 | Synchronised time, UTC or explicit offset | Partial | Journals record epoch timestamps. The gateway log pattern is `%d{HH:mm:ss.SSS}`: no date and no zone (gap 4). |
| V16.2.3 | 2 | Logs only to documented destinations | Met | Journals to `work_dir`, logs to stdout. Nothing else. |
| V16.2.4 | 2 | Logs readable by the log processor | Met | The tailers convert journals to JSON-LD and ClickHouse rows. |
| V16.2.5 | 2 | Sensitive data logged by protection level | Partial | The header allowlist and journal levels do this. Query strings are the recorded exception (V14.2.1); log lines include the request URI (`StandardErrorHandler`). |
| V16.3.1 | 2 | All authentication operations logged | Gap | `BasicAuth` logs nothing. The journal records the 401 only if the route is journaled (gap 9). |
| V16.3.2 | 2 | Failed authorisation logged | Gap | As V16.3.1, for `Require*` refusals (gap 9). |
| V16.3.3 | 2 | Security control bypass attempts logged | Partial | Path, `Transfer-Encoding` and regex-budget refusals are logged, but at DEBUG (path/TE) or unstructured WARN (regex budget) (gap 9). |
| V16.3.4 | 2 | Unexpected errors and control failures logged | Met | `StandardErrorHandler` logs upstream failures and unexpected errors with the request ID. |
| V16.4.1 | 2 | Log injection prevented | Met | Journals are binary with length-prefixed fields; header text is ISO-8859-1 without CR/LF. Undertow refuses raw CR/LF in the request line, so a logged URI stays on one line. |
| V16.4.2 | 2 | Logs protected from access and modification | Met | Journals 0640 in a 0750 directory ([#48](https://github.com/ethlo/r7/pull/48), `JournalFilePermissionsTest`); sealed segments carry integrity records. |
| V16.4.3 | 2 | Logs shipped to a separate system | Operator | The tailers ship journals to ClickHouse or JSON sinks; running them is the deployment's decision. |
| V16.5.1 | 2 | Generic error messages | Partial | Errors carry no stack traces or internals, except that the no-upstream 503 names the route ID (gap 10). |
| V16.5.2 | 2 | Secure operation when dependencies fail | Met | `CircuitBreaker`, fallback routes ([#41](https://github.com/ethlo/r7/pull/41)), and 502/503 on upstream failure. |
| V16.5.3 | 2 | Fail securely, never open | Met | A filter exception fails closed with 500 and skips the upstream (`docs/config.md` §3); regex budget exhaustion is refused, not skipped ([#53](https://github.com/ethlo/r7/pull/53)). |

## V17 WebRTC

Not applicable: No WebRTC.

<details><summary>7 requirements</summary>

| ID | L | Requirement |
|---|---|---|
| V17.1.1 | 2 | TURN reserved-address restriction |
| V17.2.1 | 2 | DTLS key management |
| V17.2.2 | 2 | DTLS cipher suites |
| V17.2.3 | 2 | SRTP authentication |
| V17.2.4 | 2 | Malformed SRTP resilience |
| V17.3.1 | 2 | Signalling flood resilience |
| V17.3.2 | 2 | Malformed signalling resilience |

</details>

---

Requirement IDs and levels are from the [OWASP Application Security Verification Standard 5.0.0](https://github.com/OWASP/ASVS/tree/v5.0.0_release) (CC BY-SA 4.0). The requirement column is a short paraphrase; the standard's own wording is authoritative.
