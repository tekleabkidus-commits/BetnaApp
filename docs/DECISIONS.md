# Agreed product decisions

- App name: Betna. Android only. Kotlin native shell around an unmodified third-party website.
- Compact responsive header: home, reload, tab picker, close and options, with a loading spinner/progress and current host. Main tab cannot be closed.
- Keep live pages when the user returns. Do not clear cookies or web storage, and do not automatically reload on resume or remote settings refresh. Save tab metadata and WebView history encrypted locally; after process death, restore what WebView permits. No promise of preserving a live JavaScript game after Android/website termination. Back at the homepage backgrounds the task.
- Telegram support username, public download page, stable verified APK link and share-link counters are managed by the owner.
- Back-office access requires authenticator two-factor setup and has one-use recovery codes. Configuration/campaign changes support selected test devices, preview and promotion. Maintenance notices can be optional or blocking.
- Opening-only popups have a short remotely configured launch window; missed or late responses are skipped rather than shown over later browsing.
- All public external website domains are permitted. Same-host navigation stays in its tab; a link from the main tab to another host opens an external tab. Non-HTTP app links are handled by Android, never by a JavaScript bridge.
- External tabs expire silently after 60 minutes by default, including the selected tab; server-configurable duration, basis, enablement and tab limit. Main tab never expires. Overdue tabs expire on resume.
- Android Autofill only; do not scrape, log, upload or store passwords in the app or admin database. Standard cookie sessions follow website policy.
- Distribution is direct APK download, not Google Play. Installer consent is preserved. App-controlled minimum version enforces required updates; optional prompts can be postponed.
- Laravel Cloud hosts the Laravel admin/API. PostgreSQL, a queue worker, scheduler and durable S3-compatible object storage are required in production.
- Campaigns are manual, scheduled, recurring or app-event triggered with installation-based filters, caps, quiet hours and eligibility audit records.
- Reports count installations, not verified users; foreground presence is approximate. APK download is not installation; update completion is observed at the next launch.
- No site account, deposit, balance or transaction integration. Never automatically retry POSTs or switch domains during browsing. Homepage domain fallback requires explicit user retry.
- DNS-only connection routing is not a VPN and cannot bypass IP/SNI blocking. Use a local CONNECT proxy plus AndroidX WebView ProxyController so TLS remains end-to-end; no TLS interception.
- The service stores no page contents, query strings, credentials or financial information in telemetry.

## Information still needed from the owner

1. Actual website URL, Telegram username and optional logo.
2. Private GitHub repository link.
3. Laravel Cloud account/project and final API/custom domain.
4. Firebase project/service account for push notifications.
5. Release signing key ownership and Android package identifier.
6. A real Android device on the affected network for full website acceptance tests.
