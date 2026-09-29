# OWASP ASVS 5.0 Level 2 self-assessment

This file is r7's evidence for the claim *self-assessed against OWASP ASVS 5.0.0, Level 2*. It lists every Level 1 and Level 2 requirement, 253 in total. Each row gives the requirement's status and its evidence: the control that meets it, the reason it does not apply, or what still has to happen before it is met.

This is a working document, not yet a claim. It becomes one when every **Gap** and **Partial** row is closed or turned into a recorded decision, and someone outside the project has reviewed the evidence. Keep it current: a PR that removes a control, or adds a feature that brings a chapter into scope, updates the rows it touches. A JWT filter, for example, would bring V9 into scope.

## Scope and assumptions

- **r7 is the edge of an application, not the application.** Requirements about user accounts, sessions, business logic and payload content belong to the upstream services and are marked N/A here. r7's own contribution is routing, request hygiene, route-level access control, limits and the audit journal.
- **The data-plane listener is plaintext by design.** r7 runs on a private network or behind a TLS-terminating load balancer, and still expects hostile traffic on that network. TLS requirements for the listener are Accepted or Operator, never Met.
- **The management port is unauthenticated, so it must stay private.** The JVM binds it to 127.0.0.1 by default. The container images set `R7_MANAGEMENT_HOST=0.0.0.0` so that a published status port works; in a container deployment, keeping that port off untrusted networks is the operator's job.
- **Operators write the routes.** Where r7 provides a control but the configuration decides whether it is used (CORS, rate limits, response headers, journal levels), the row is **Operator** and the evidence names the control.

## Status legend

| Status | Meaning |
|---|---|
| Met | r7 satisfies it; the evidence names the code, PR or test. |
| Partial | Satisfied in part. The row names what is missing: a numbered gap, a roadmap item, or a recorded decision (Accepted, with its row). |
| Gap | Not satisfied yet; a numbered gap below tracks the fix. |
| Accepted | Deliberately not satisfied, with the reason recorded. |
| Operator | r7 provides the means; whether it holds depends on deployment or configuration. |
| N/A | r7 has no such feature; where one exists, it belongs to the upstream. |

## Summary

| Chapter | Met | Partial | Gap | Accepted | Operator | N/A |
|---|---:|---:|---:|---:|---:|---:|
| V1 Encoding and Sanitization | 13 | 2 |  |  |  | 12 |
| V2 Validation and Business Logic | 4 | 1 |  |  | 1 | 5 |
| V3 Web Frontend Security | 10 |  |  |  | 8 | 1 |
| V4 API and Web Service | 3 |  |  | 1 | 1 | 5 |
| V5 File Handling | 3 |  |  |  |  | 6 |
| V6 Authentication | 5 |  |  | 1 | 1 | 28 |
| V7 Session Management |  |  |  |  |  | 18 |
| V8 Authorization | 3 |  |  |  |  | 4 |
| V9 Self-contained Tokens |  |  |  |  |  | 7 |
| V10 OAuth and OIDC |  |  |  |  |  | 29 |
| V11 Cryptography | 5 | 2 |  | 1 |  | 6 |
| V12 Secure Communication | 1 |  |  | 2 | 5 | 1 |
| V13 Configuration | 7 | 1 | 1 |  | 4 |  |
| V14 Data Protection | 3 | 3 |  | 2 |  | 1 |
| V15 Secure Coding and Architecture | 10 | 3 |  |  |  |  |
| V16 Security Logging and Error Handling | 10 | 3 |  |  | 3 |  |
| V17 WebRTC |  |  |  |  |  | 7 |
| **Total** | **77** | **15** | **1** | **7** | **23** | **130** |

## Gaps

The Partial and Gap rows refer to these by number. Gaps 1–6 and 10 are closed by #74, and gap 8 by #76. This file is stacked on both, so the rows it marks Met describe the tree it ships in.

1. ~~Refuse TRACE~~ (V13.4.4). Closed by [#74](https://github.com/ethlo/r7/pull/74): TRACE gets 501 before routing.
2. ~~Harden r7's own responses~~ (V3.2.1, V3.4.4, V4.1.1). Closed by [#74](https://github.com/ethlo/r7/pull/74): `nosniff` and `text/plain; charset=utf-8` on everything r7 writes.
3. ~~CSP on the dashboard~~ (V3.4.3, V3.4.6). Closed by [#74](https://github.com/ethlo/r7/pull/74): the policy allows the page's single script by hash, with `default-src`, `base-uri`, `form-action` and `frame-ancestors` all `'none'`; the inline handlers are gone.
4. ~~Log timestamps with date and zone~~ (V16.2.2). Closed by [#74](https://github.com/ethlo/r7/pull/74): ISO-8601 in UTC.
5. ~~Cookie defaults~~ (V3.3.1, V3.3.2, V3.3.4). Closed by [#74](https://github.com/ethlo/r7/pull/74): `Secure`, `HttpOnly` and `SameSite=Lax` unless configured otherwise.
6. ~~Brute-force guidance for BasicAuth~~ (V6.1.1, V6.3.1). Closed by [#74](https://github.com/ethlo/r7/pull/74): the docs cover the missing lockout, pairing with a `RateLimiter`, bcrypt cost, and journaling failed logins.
7. **Security documentation** (V2.1.3, V11.1.2, V13.1.1, V14.1.x, V15.1.3, V15.2.2, V16.1.1). `SECURITY.md` is done ([#75](https://github.com/ethlo/r7/pull/75)). Still to write: a `docs/security.md` covering data classification, every connection r7 makes, the cryptographic inventory, the log and journal inventory, resource-demanding features and every limit.
8. ~~Repeated parameters and headers~~ (V15.3.7). Closed by [#76](https://github.com/ethlo/r7/pull/76): every occurrence of a repeated name must pass a value check.
9. **Refusals before routing are not journaled** (V16.3.3). Ambiguous paths, bad `Transfer-Encoding` and TRACE are refused before a route is chosen, so no journal records them, and the log line is at DEBUG. Journal them, or log them at INFO through a dedicated logger.
10. ~~Route ID in the no-upstream 503~~ (V16.5.1). Closed by [#74](https://github.com/ethlo/r7/pull/74): the body is generic; the route ID is logged and journaled.
11. **bcrypt cost floor** (V11.4.2). Any cost from 4 to 31 is accepted. Refuse, or warn at load about, a cost below 10.
12. **Short-lived credentials towards upstreams** (V13.2.1). r7 can present only static credentials to an upstream. Meeting this needs mTLS client certificates or short-lived tokens (for example an OAuth client-credentials exchange); otherwise, record it as accepted for deployments where the network is the trust boundary.

Also recorded:

- **Accepted:** query strings are journaled unredacted (V14.2.1). This was a deliberate decision during the security review; revisit it if journals leave the host.
- **Accepted:** the listener is plaintext and `BasicAuth` is single-factor (V12.2.1, V12.3.1, V4.4.1, V6.3.3); see Scope.
- **Accepted:** `BasicAuth` caches SHA-256 digests of verified credentials in memory to avoid a bcrypt round per request (V14.2.2).
- **Roadmap:** fuzzing the journal decoder and the request guards with Jazzer moves V1.4.1 and V1.4.2 to Met. Publishing an SBOM with each image moves V15.1.2 to Met.

## V1 Encoding and Sanitization

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V1.1.1 | 2 | Decode input to canonical form once, before validation | Met | Undertow decodes the path once; `RequestPathGuard` then refuses anything a second decode or the upstream could read differently (dot-segments, `%2e`/`%2f`/`%5c`/`%25` left after decoding, backslash, control characters) with 400, before routing ([#39](https://github.com/ethlo/r7/pull/39), `RequestPathGuardTest`). |
| V1.1.2 | 2 | Encode output as the final step, for its interpreter | Met | Rewritten paths are re-encoded by `PathEncoder` as they are handed to the proxy ([#40](https://github.com/ethlo/r7/pull/40), `PathEncoderTest`); header values are validated at the point of mutation (`TextValuesTest`); the dashboard escapes at render time ([#55](https://github.com/ethlo/r7/pull/55)). |
| V1.2.1 | 1 | Context-aware output encoding (HTML, headers) | Met | Header names and values refuse C0 controls other than HTAB, DEL, and anything outside ISO-8859-1, at the point of mutation ([#74](https://github.com/ethlo/r7/pull/74), `UndertowGatewayHeadersValidationTest`, `MutableFastGatewayHeadersTest`). This matters for upstream requests, which Undertow's client writes verbatim; it already blanks CR/LF in responses. The dashboard escapes every config-derived value ([#55](https://github.com/ethlo/r7/pull/55)). |
| V1.2.2 | 1 | Encode untrusted data in built URLs; safe URL schemes only | Met | `PathEncoder` ([#40](https://github.com/ethlo/r7/pull/40)); `TemplateRedirect` refuses computed locations that leave the template's origin, change scheme or start with `//` ([#49](https://github.com/ethlo/r7/pull/49), `TemplateRedirectFactoryTest`). |
| V1.2.3 | 1 | Encode when building JavaScript/JSON | Met | Management JSON is produced by Jackson. The dashboard escapes config-derived values and never interpolates them into handlers ([#55](https://github.com/ethlo/r7/pull/55)); it has no inline handlers left ([#74](https://github.com/ethlo/r7/pull/74)). The tailers serialise with Jackson. |
| V1.2.4 | 1 | Parameterised database queries | N/A | r7 has no database. The ClickHouse tailer writes `JSONEachRow` lines with a JSON generator; no SQL is built. |
| V1.2.5 | 1 | OS command injection | N/A | r7 never starts processes. |
| V1.2.6 | 2 | LDAP injection | N/A | No LDAP. |
| V1.2.7 | 2 | XPath injection | N/A | No XPath. |
| V1.2.8 | 2 | LaTeX injection | N/A | No LaTeX. |
| V1.2.9 | 2 | Escape regex metacharacters in untrusted input | Met | Patterns come only from configuration, never from request data. Matching untrusted input against them runs under `RegexBudget` ([#53](https://github.com/ethlo/r7/pull/53)). |
| V1.3.1 | 1 | Sanitise WYSIWYG HTML | N/A | r7 accepts no HTML content. |
| V1.3.2 | 1 | No eval or dynamic code execution | Met | No scripting engines or expression languages. Filters and predicates are compiled classes registered through `@AutoService`. |
| V1.3.3 | 2 | Sanitise data before dangerous contexts | Met | Header text refuses line breaks and other controls when set ([#74](https://github.com/ethlo/r7/pull/74)); paths refuse controls and are re-encoded ([#39](https://github.com/ethlo/r7/pull/39), [#40](https://github.com/ethlo/r7/pull/40)). |
| V1.3.4 | 2 | Sanitise user-supplied SVG | N/A | r7 accepts no uploads to serve back. |
| V1.3.5 | 2 | Sanitise template/markup languages | N/A | No user-supplied markup is rendered. |
| V1.3.6 | 2 | SSRF protection | Met | Upstream targets come only from configuration. Absolute-form requests naming another host are refused (400). `X-Forwarded-For`/`X-Real-IP` are parsed as literals and never resolved (`RemoteAddressResolverTest`). |
| V1.3.7 | 2 | Template injection | Met | `TemplateRedirect` templates are configuration; request data fills placeholders and cannot introduce template syntax ([#49](https://github.com/ethlo/r7/pull/49)). |
| V1.3.8 | 2 | JNDI injection | N/A | No JNDI lookups. Logback is configured from a bundled file. |
| V1.3.9 | 2 | Memcache injection | N/A | No memcache. |
| V1.3.10 | 2 | Format string safety | Met | Logging uses SLF4J `{}` placeholders; no request data reaches a format string. |
| V1.3.11 | 2 | SMTP/IMAP injection | N/A | No mail. |
| V1.4.1 | 2 | Memory-safe buffers | Partial | Java bounds-checks heap access, but the journal uses `MappedByteBuffer` and off-heap reads, and the decoder parses untrusted bytes on recovery. Bounds are enforced by the format (`FORMAT.md`) and `JournalIntegrityTest`. Fuzzing the decoder and recovery with Jazzer closes this (roadmap). |
| V1.4.2 | 2 | Prevent integer overflow | Partial | Sizes and counts are `long`; config bounds are validated (`LimitsConfigValidationTest`, `StorageConfigValidationTest`). Length fields read from journal segments need the same fuzzing as V1.4.1 (roadmap). |
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
| V2.4.1 | 2 | Anti-automation controls | Operator | `RateLimiter` per client address, keyed by IPv6 /64 by default ([#52](https://github.com/ethlo/r7/pull/52)), on the routes the operator chooses. Always on, but only for authentication CPU: bcrypt concurrency is bounded ([#47](https://github.com/ethlo/r7/pull/47)). |

## V3 Web Frontend Security

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V3.2.1 | 1 | Prevent rendering in the wrong context | Met | Every response r7 writes itself carries `nosniff` and an explicit `text/plain; charset=utf-8` ([#74](https://github.com/ethlo/r7/pull/74)); the management port also sends `X-Frame-Options: DENY`, `no-store` and a CSP ([#46](https://github.com/ethlo/r7/pull/46), [#74](https://github.com/ethlo/r7/pull/74)). Proxied responses are the upstream's; operators can add headers with `SetResponseHeader`. |
| V3.2.2 | 1 | Render text with safe DOM functions | Met | Dashboard values are escaped and navigation uses `data-` attributes with one delegated listener ([#55](https://github.com/ethlo/r7/pull/55), [#74](https://github.com/ethlo/r7/pull/74)). |
| V3.3.1 | 1 | Cookies Secure, with a `__Host-`/`__Secure-` prefix | Operator | `SetResponseCookie` sets `Secure` unless configured `secure: false` ([#74](https://github.com/ethlo/r7/pull/74)); the cookie name, and so the prefix, is the operator's. Behind TLS termination the browser sees HTTPS, so `Secure` holds. |
| V3.3.2 | 2 | SameSite set per cookie purpose | Met | `SameSite=Lax` unless configured otherwise; `None` without `secure` is refused at load ([#74](https://github.com/ethlo/r7/pull/74), `SetResponseCookieFactoryTest`). |
| V3.3.3 | 2 | `__Host-` prefix | Operator | The cookie name is the operator's. |
| V3.3.4 | 2 | HttpOnly for cookies scripts must not read | Met | `HttpOnly` unless configured `http_only: false` ([#74](https://github.com/ethlo/r7/pull/74)). |
| V3.4.1 | 1 | HSTS | Operator | Needs TLS, which r7's listener does not terminate. Set it at the TLS terminator, or with `SetResponseHeader` behind one. |
| V3.4.2 | 1 | CORS allow-origin fixed or allowlisted | Operator | Where configured, `Cors` echoes only allowlisted origins, adds `Vary: Origin`, answers only real preflights and overrules upstream grants ([#51](https://github.com/ethlo/r7/pull/51), `CorsFactoryTest`, `CorsE2ETest`). Which routes carry it is the operator's decision. |
| V3.4.3 | 2 | Content-Security-Policy | Met | Management: `default-src 'none'`, the one script allowed by hash, `object-src` covered by `default-src`, `base-uri 'none'` ([#74](https://github.com/ethlo/r7/pull/74), `StatusHandlerCspTest`). Proxied responses: the upstream's, or the operator's through `SetResponseHeader`. |
| V3.4.4 | 2 | `X-Content-Type-Options: nosniff` on all responses | Met | On everything r7 writes, management included ([#46](https://github.com/ethlo/r7/pull/46), [#74](https://github.com/ethlo/r7/pull/74)). Proxied responses are the upstream's. |
| V3.4.5 | 2 | Referrer-Policy | Operator | Management sends `no-referrer` ([#46](https://github.com/ethlo/r7/pull/46)). Data-plane pages are the upstream's; `SetResponseHeader` can add one. |
| V3.4.6 | 2 | CSP `frame-ancestors` | Met | Management sends `frame-ancestors 'none'` and `X-Frame-Options: DENY` ([#46](https://github.com/ethlo/r7/pull/46), [#74](https://github.com/ethlo/r7/pull/74)). |
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
| V4.1.1 | 1 | Content-Type with charset on every body | Met | r7's own bodies are `text/plain; charset=utf-8` ([#74](https://github.com/ethlo/r7/pull/74)), the dashboard is `text/html; charset=utf-8` ([#76](https://github.com/ethlo/r7/pull/76), `ManagementEndpointTest`), and management JSON is `application/json` (UTF-8 by definition). |
| V4.1.2 | 2 | HTTP-to-HTTPS redirects only on user-facing endpoints | N/A | r7 does not redirect to HTTPS. TLS is in front of it. |
| V4.1.3 | 2 | Intermediary-set headers cannot be overridden by clients | Met | `X-Forwarded-*`, `Forwarded` and `X-Real-IP` are stripped from untrusted peers before proxying ([#43](https://github.com/ethlo/r7/pull/43), `UpstreamHeaderSanitizerTest`); the client address is taken only from `trusted_proxies` (`RemoteAddressResolverTest`). `BasicAuth` removes the consumed `Authorization` ([#47](https://github.com/ethlo/r7/pull/47)). |
| V4.2.1 | 2 | Message boundaries (request smuggling) | Met | Non-canonical `Transfer-Encoding` refused with 400 and a closed connection ([#45](https://github.com/ethlo/r7/pull/45), `TransferEncodingGuardTest`); CL+TE, duplicate CL and bare LF rejected (report, "Already done well"); hop-by-hop headers stripped ([#43](https://github.com/ethlo/r7/pull/43)); h2c off by default ([#56](https://github.com/ethlo/r7/pull/56), `Http2DefaultTest`); truncated chunked bodies never reach the upstream as complete ([#44](https://github.com/ethlo/r7/pull/44), `RequestSizeLimitStreamingTest`). A differential test against nginx/Node is in roadmap phase 3. |
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
| V6.1.1 | 1 | Documented anti-brute-force controls | Met | `docs/config.md` says `BasicAuth` has no lockout, and shows a `RateLimiter` before it and journaling of failed logins with `status_overrides` ([#74](https://github.com/ethlo/r7/pull/74)). It also documents the bcrypt concurrency cap ([#47](https://github.com/ethlo/r7/pull/47)). |
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
| V6.3.1 | 1 | Credential stuffing and brute-force controls | Operator | Unknown users take the same time as known ones, and bcrypt concurrency is bounded ([#47](https://github.com/ethlo/r7/pull/47)). Guessing is limited per client by the `RateLimiter` the documentation requires on exposed routes ([#74](https://github.com/ethlo/r7/pull/74)). |
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
| V11.1.2 | 2 | Cryptographic inventory | Partial | Uses: bcrypt (`BasicAuth` verification); SHA-256 (`BasicAuth` cache keys, `RedactUtil` fingerprints, `bin/flatc.sha256`); CRC32C (journal corruption detection, not a security control); JDK TLS and the JVM trust store for `https` upstreams. No keys or certificates of r7's own. Moving this list into `docs/security.md` with an owner makes it a maintained inventory (gap 7). |
| V11.2.1 | 2 | Industry-validated implementations | Met | The JDK's TLS stack and a bcrypt library; nothing hand-rolled. |
| V11.2.2 | 2 | Crypto agility | Accepted | SHA-256 appears only in fingerprints and in-memory cache keys, with no stored state to migrate, so replacing it is a code change. bcrypt is the only accepted password hash, and each hash names its own scheme and cost, so another scheme can be added alongside it. Upstream TLS follows JDK security properties. |
| V11.2.3 | 2 | At least 128-bit security | Met | JDK default TLS suites. bcrypt is outside this metric. |
| V11.3.1 | 1 | No ECB or weak padding | N/A | r7 encrypts nothing itself. |
| V11.3.2 | 1 | Approved ciphers and modes | Met | Upstream TLS uses the JDK defaults. |
| V11.3.3 | 2 | Authenticated encryption | N/A | No application-level encryption. |
| V11.4.1 | 1 | Approved hash functions | Met | SHA-256 for binary verification. CRC32C detects corruption and is not a security control. |
| V11.4.2 | 2 | Password hashing KDF | Partial | bcrypt, with format and cost 4-31 validated at load (`BCryptTest`, `BasicAuthFactoryTest`). Docs recommend a cost of at least 10 ([#74](https://github.com/ethlo/r7/pull/74)), but a lower cost is still accepted (gap 11). |
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
| V13.2.1 | 2 | Backend communication authenticated | Gap | r7 can present only static credentials to an upstream: `InjectBasicAuth`, or a fixed header set with `SetRequestHeader`. ASVS 5.0 rules out unchanging passwords and API keys here, and r7 supports neither mTLS client certificates nor short-lived tokens towards upstreams (gap 12). |
| V13.2.2 | 2 | Least-privilege accounts for backend communication | Operator | The accounts r7 uses towards upstreams are whatever credentials the operator configures. r7's own process runs as UID 65532 on distroless bases, with journals at 0640 in a 0750 directory ([#48](https://github.com/ethlo/r7/pull/48)). |
| V13.2.3 | 2 | No default credentials | Met | No shipped credentials. |
| V13.2.4 | 2 | Allowlist of external resources | Met | Upstream targets are an explicit list in configuration; requests cannot choose one (V1.3.6). |
| V13.2.5 | 2 | Server-side allowlist for outbound requests | Met | As V13.2.4. |
| V13.3.1 | 2 | Secrets management solution | Operator | `${VAR}` interpolation lets secrets come from the environment or a mounted secret rather than the YAML; the vault is the deployment's. |
| V13.3.2 | 2 | Least privilege for secrets | Operator | Access to the YAML, the environment and mounted secrets is the deployment's. r7 keeps secrets out of its own outputs: `@Sensitive` values are masked in management output and URL credentials redacted in summaries ([#46](https://github.com/ethlo/r7/pull/46)). |
| V13.4.1 | 1 | No source control metadata served | Met | `StaticContent` refuses dotfiles, `.git/` included, unless `serve_hidden_files` is set ([#50](https://github.com/ethlo/r7/pull/50)). |
| V13.4.2 | 2 | Debug modes off | Met | No debug endpoints. Log levels default to INFO/WARN (`default-logback.xml`). |
| V13.4.3 | 2 | No directory listings | Met | `list_directory` defaults to false. |
| V13.4.4 | 2 | TRACE not supported | Met | TRACE is answered `501 Not Implemented` before routing and never reaches an upstream ([#74](https://github.com/ethlo/r7/pull/74), `R7FullSpecMatrixTest`). |
| V13.4.5 | 2 | Documentation and monitoring endpoints not exposed | Operator | The management port binds to 127.0.0.1 by default ([#46](https://github.com/ethlo/r7/pull/46), `ManagementConfigTest`). The container images set `R7_MANAGEMENT_HOST=0.0.0.0` so a published status port works, which makes restricting that port a deployment duty (see Scope). |

## V14 Data Protection

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V14.1.1 | 2 | Sensitive data identified and classified | Partial | In code: journal header allowlist (`RedactingHeadersTest`), `@Sensitive` config ([#46](https://github.com/ethlo/r7/pull/46)). Not written down as a classification (gap 7). |
| V14.1.2 | 2 | Protection requirements per level | Partial | As V14.1.1. |
| V14.2.1 | 1 | No sensitive data in URLs | Accepted | r7 cannot stop clients putting secrets in query strings. It journals the start line unredacted, a deliberate decision recorded against F10. Operators can lower the journal level, or strip parameters with `RemoveQueryParameter`. |
| V14.2.2 | 2 | Sensitive data not cached in server components | Accepted | r7 has no response cache, and bodies are streamed. One in-memory cache holds sensitive data: `BasicAuth` keeps the SHA-256 digest of each verified `Authorization` value, at most 1,024 entries, expiring 15 minutes after last use, so bcrypt does not run on every request. It is never persisted or journaled, and a reload empties it. The accepted risk: a heap dump would let someone guess the cached passwords at SHA-256 speed rather than bcrypt speed. Journals, the only persistent copy of request data, are 0640 ([#48](https://github.com/ethlo/r7/pull/48)) and retained by the reaper. |
| V14.2.3 | 2 | No sensitive data to untrusted parties | Met | r7 sends data only to configured upstreams and local journals; the dashboard loads no third-party resources. |
| V14.2.4 | 2 | Controls for sensitive data (retention, logging) | Partial | Journal levels per route and direction, header allowlist, reaper retention. Query values are the recorded exception (Accepted, V14.2.1). |
| V14.3.1 | 1 | Client storage cleared on logout | N/A | No sessions. |
| V14.3.2 | 2 | Anti-caching headers for sensitive data | Met | The management port sends `Cache-Control: no-store` ([#46](https://github.com/ethlo/r7/pull/46)). `RemoveCacheHeaders`/`SetResponseHeader` are available for data-plane routes. |
| V14.3.3 | 2 | No sensitive data in browser storage | Met | The dashboard stores nothing. |

## V15 Secure Coding and Architecture

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V15.1.1 | 1 | Documented remediation time frames | Met | `SECURITY.md` sets fix deadlines by severity (Critical 7 days, High 30, Medium 90, Low next release) for r7 and its reachable dependencies ([#75](https://github.com/ethlo/r7/pull/75)). |
| V15.1.2 | 2 | SBOM and trusted component sources | Partial | A CycloneDX SBOM is built on every CI run and scanned by OSV-Scanner ([#59](https://github.com/ethlo/r7/pull/59)); dependencies come from Maven Central, base images are pinned by digest ([#72](https://github.com/ethlo/r7/pull/72)), and `flatc` is verified by checksum ([#60](https://github.com/ethlo/r7/pull/60)). The SBOM is not yet published with each image (roadmap). |
| V15.1.3 | 2 | Documented resource-demanding functionality | Partial | Known expensive paths: bcrypt, regex matching, FULL body journaling, static content. Each is bounded in code ([#47](https://github.com/ethlo/r7/pull/47), [#53](https://github.com/ethlo/r7/pull/53), backpressure), but they are not listed in one place (gap 7). |
| V15.2.1 | 1 | No components past remediation time frames | Met | OSV-Scanner fails CI on any advisory not accepted, with an expiry, in `osv-scanner.toml` ([#59](https://github.com/ethlo/r7/pull/59)); Dependabot proposes updates weekly ([#58](https://github.com/ethlo/r7/pull/58)); `SECURITY.md` sets the time frames they are measured against ([#75](https://github.com/ethlo/r7/pull/75)). |
| V15.2.2 | 2 | Defences against resource exhaustion | Partial | In code: regex budget ([#53](https://github.com/ethlo/r7/pull/53), shared per check since [#76](https://github.com/ethlo/r7/pull/76)), bcrypt semaphore ([#47](https://github.com/ethlo/r7/pull/47)), listener limits (header size and count, parse timeout, entity size), `RateLimiter`, `CircuitBreaker`, journal backpressure. ASVS asks for these to follow documented decisions, and that documentation is gap 7 (V15.1.3). |
| V15.2.3 | 2 | No extraneous functionality in production | Met | Distroless images hold only the jar or native binary; test code is not packaged. |
| V15.3.1 | 1 | Only the required fields returned | Met | Management summaries mask `@Sensitive` values and fingerprint secrets ([#46](https://github.com/ethlo/r7/pull/46)). |
| V15.3.2 | 2 | Outbound calls do not follow redirects | Met | The proxy passes upstream 3xx responses to the client and never follows them. |
| V15.3.3 | 2 | Mass assignment | Met | Config deserialises with `FAIL_ON_UNKNOWN_PROPERTIES` onto records. |
| V15.3.4 | 2 | Original client IP transferred through trusted fields | Met | `RemoteAddressResolver` believes forwarding headers only from `trusted_proxies`, right to left, and fails closed (`RemoteAddressResolverTest`); untrusted peers' forwarding headers are stripped before proxying ([#43](https://github.com/ethlo/r7/pull/43)). |
| V15.3.5 | 2 | Strict types and comparisons | Met | Java, with typed config records. |
| V15.3.6 | 2 | No prototype pollution | Met | The dashboard does not merge untrusted objects. |
| V15.3.7 | 2 | HTTP parameter pollution | Met | Every value check (`QueryParameter`, `RequestHeader`, `Cookie`, their `Match*` forms and the `RequireMatch*` filters) passes only if every occurrence of a repeated name passes, so `?role=user&role=admin` cannot satisfy a check the upstream reads differently ([#76](https://github.com/ethlo/r7/pull/76), `RepeatedValuesTest`). Duplicate `Authorization` values are refused ([#47](https://github.com/ethlo/r7/pull/47)). |

## V16 Security Logging and Error Handling

| ID | L | Requirement | Status | Evidence |
|---|---|---|---|---|
| V16.1.1 | 2 | Logging inventory | Partial | `docs/journaling.md` covers journals; application logs (logback) are not inventoried (gap 7). |
| V16.2.1 | 2 | Who/what/when/where metadata | Met | Every journaled exchange records request ID, client IP and its source, start line, status and timings (`journal.fbs` `EndExchange`). |
| V16.2.2 | 2 | Synchronised time, UTC or explicit offset | Met | Journals record epoch timestamps. Logs are ISO-8601 in UTC with the date ([#74](https://github.com/ethlo/r7/pull/74)). Clock sync is the host's. |
| V16.2.3 | 2 | Logs only to documented destinations | Met | Journals to `work_dir`, logs to stdout. Nothing else. |
| V16.2.4 | 2 | Logs readable by the log processor | Met | The tailers convert journals to JSON-LD and ClickHouse rows. |
| V16.2.5 | 2 | Sensitive data logged by protection level | Partial | The header allowlist and journal levels do this. Query strings are the recorded exception (Accepted, V14.2.1); upstream-failure log lines include the request URI (`StandardErrorHandler`). |
| V16.3.1 | 2 | All authentication operations logged | Operator | Every `BasicAuth` refusal is a journaled `401` with request ID, client address and time. `status_overrides` records them on routes that otherwise journal nothing, as the `BasicAuth` docs show ([#74](https://github.com/ethlo/r7/pull/74)). Successful logins are journaled with the user's fingerprint (`gateway.auth.basic.user`) when the route is journaled. |
| V16.3.2 | 2 | Failed authorisation logged | Operator | As V16.3.1: `Require*` refusals are journaled with their status, and `status_overrides` covers otherwise unjournaled routes. |
| V16.3.3 | 2 | Security control bypass attempts logged | Partial | Refusals after routing (rate limits, size limits, regex budget) are journaled. Refusals before routing (ambiguous path, `Transfer-Encoding`, TRACE) have no route and so no journal, only a DEBUG log line (gap 9). |
| V16.3.4 | 2 | Unexpected errors and control failures logged | Met | `StandardErrorHandler` logs upstream failures and unexpected errors with the request ID. |
| V16.4.1 | 2 | Log injection prevented | Met | Journals are binary with length-prefixed fields; header text is ISO-8859-1 without CR/LF. Undertow refuses raw CR/LF in the request line, so a logged URI stays on one line. |
| V16.4.2 | 2 | Logs protected from access and modification | Met | Journals 0640 in a 0750 directory ([#48](https://github.com/ethlo/r7/pull/48), `JournalFilePermissionsTest`); sealed segments carry integrity records. |
| V16.4.3 | 2 | Logs shipped to a separate system | Operator | The tailers ship journals to ClickHouse or JSON sinks; running them is the deployment's decision. |
| V16.5.1 | 2 | Generic error messages | Met | r7's error bodies carry no stack traces, internals or configuration names. The no-upstream 503 no longer names the route, which is logged and journaled instead ([#74](https://github.com/ethlo/r7/pull/74), `NoUpstreamResponseTest`). |
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

Requirement IDs and levels are from the [OWASP Application Security Verification Standard 5.0.0](https://github.com/OWASP/ASVS/tree/v5.0.0_release) (CC BY-SA 4.0). The Requirement column paraphrases each requirement; the standard's own wording is authoritative.
