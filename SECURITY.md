# Security policy

## Reporting a vulnerability

Email **security-disclosure@ethlo.com**. Please do not open a public issue or pull request
for a suspected vulnerability.

A useful report says which version or commit you tested, the configuration involved (the
relevant part of `routes.yaml` and `server.yaml`), and the request that shows the problem. A
raw request, as sent on the wire, is the most useful form for anything to do with parsing or
routing.

What to expect:

| Step | Target |
|---|---|
| Acknowledgement of your report | 3 working days |
| Initial assessment, including whether we consider it a vulnerability and its severity | 10 working days |
| Fix released, or a mitigation published | Within the time frame for its severity, below |

We credit reporters in the release notes and advisory unless they ask not to be. We publish
fixed vulnerabilities as GitHub security advisories.

## Supported versions

r7 has not had a release yet. Until it does, only the `main` branch is supported, and fixes
land there. Once releases start, the latest minor release will receive security fixes.

## Scope

In scope: anything that lets a request get past a control r7 is configured to enforce. That
includes route-level access control, path handling, request framing and smuggling, header
handling, limits, and the journal's integrity and confidentiality.

These are deployment assumptions, not vulnerabilities:

- The data-plane listener is plaintext HTTP by design. r7 is meant to run on a private
  network or behind a TLS-terminating load balancer.
- The management port (`:18888`) is unauthenticated and must not be reachable from untrusted
  networks. The JVM binds it to 127.0.0.1 by default; the container images bind it to all
  interfaces so that a published port works. It is read-only and masks configured secrets,
  but it shows the route configuration, upstream targets and live metrics to anyone who can
  reach it.
- Redacted header and query parameter values in the journal are keyed fingerprints. Anyone who
  has the fingerprint key can confirm a guess at a low-entropy value, so keep it from journal
  readers (`docs/journaling.md`, "Redacted header and query parameter values").
- Upstream services are responsible for their own authentication, sessions and payload
  validation beyond what r7's filters are configured to check.

`design/asvs-l2.md` records how r7 measures up against OWASP ASVS 5.0 Level 2, including
the requirements that are accepted rather than met.

## Remediation time frames

These apply to vulnerabilities in r7 itself and to vulnerable dependencies reachable from
it. CI fails on a known advisory in a dependency (`security.yml`); an advisory that r7 cannot
reach is recorded in `osv-scanner.toml` with its reason and an expiry date instead.

| Severity (CVSS v3.1 or v4.0) | Fixed or mitigated within |
|---|---|
| Critical (9.0–10.0) | 7 days |
| High (7.0–8.9) | 30 days |
| Medium (4.0–6.9) | 90 days |
| Low (0.1–3.9) | The next release |

Dependencies without a known vulnerability are kept current through Dependabot, which
proposes updates weekly.
