# Betna implementation and verification report

Updated 30 September 2026. Betna consists of an Android website shell and a Laravel 13 / PHP 8.4 back office. The existing website is not modified.

## What was added and why

| Addition | Result and purpose |
| --- | --- |
| Betna branding | App label, simple launcher icon, back-office title and Download Betna App page use the agreed name. |
| Session preservation | Returning to the app keeps the current WebView rather than reloading the homepage. Cookies and local storage are retained; tab metadata and navigation history are saved encrypted locally. This gives the website the best chance of continuing a game. |
| Loading indicators | Startup spinner, page-loading spinner and progress bar show that a request is still running. They do not block or reload an already-open page. |
| DNS form | Add/edit/reorder HTTPS DNS providers and bootstrap addresses, assign host rules and control fallback through ordinary form fields. |
| Connection test | Separate API, DNS, website, WebView and push capability results with a report reference. The website check is a HEAD request to the configured homepage; it does not repeat the user's last form or game action. |
| Selected-device testing | Publish configuration to explicitly marked test installations, then promote to everyone or roll back by creating a new revision. Campaigns also have preview, test send and promotion controls. |
| Opening-only popups | Campaign creation includes app opening, first installation opening and first opening after an update. The launch window is remotely configurable from 2 to 30 seconds. Late responses are skipped, and interval polling cannot deliver an opening-only campaign. |
| Maintenance notice | Optional or blocking remotely controlled notice with Telegram support. It does not automatically navigate away from the current page. |
| Administrator 2FA | Required authenticator setup, replay-resistant TOTP verification, encrypted secrets and single-use recovery codes protect DNS, links, campaigns and releases. |
| Telegram support | The owner edits a username in Launch & links. The app and public support redirect use the resulting Telegram URL. |
| Download and share links | Public `/download` page, stable `/download/apk` redirect and named `/get/{slug}` links. Downloads require a verified, published APK. Named links can be disabled and have request counters. |
| Compatibility reports | Android version, phone manufacturer/model, WebView version, low-memory classification and push availability can be inspected or used in campaign filters. Startup, renderer failures, safe crash classes and foreground UI-stall events help diagnose problems. |
| Release protection | Production R8/resource shrinking, signed configuration, verified APK identity/hash/size/version, required or optional updates, and server-side staff permissions. Installation tokens can be revoked. |
| Continuous verification | GitHub workflow runs backend tests/style checks and Android unit/build/lint checks. Device instrumentation test sources are included. |

The existing capabilities remain: Home, Reload, Tabs, Close and Options; scheduled/manual/recurring notifications; language variants, audience conditions, exclusions, caps and quiet hours; installation/version/approximate online and campaign-event reports; role permissions and audit logs; bounded offline telemetry; Android password-manager Autofill.

## Browser and game-session behavior

While the process and WebView remain alive, the app does not clear cookies, clear web storage, reload on resume, or reload because settings were refreshed. Pressing Back at the main page backgrounds the task. Current browsing connections are not deliberately expired after a short idle timeout by the local HTTPS tunnel.

If Android kills the process or WebView renderer, Betna restores saved tabs and WebView navigation history where supported. This is not a snapshot of JavaScript memory or a running game. Android, network connectivity, server-side expiry and the website's own recovery behavior determine whether a game resumes. A restored page may need to load again; the app does not automatically resubmit POST forms. User-requested Home, Reload, notification actions and closing a tab can change the active page.

The main tab never expires. External tabs still follow the approved back-office rule: silent closing after 60 minutes by default, including the selected external tab, with configurable duration, opening/activity basis, enablement and tab limit. Overdue external tabs are not restored. The session-preservation switch controls saved tab/history snapshots; it does not clear website cookies.

History snapshots stay encrypted on the device and are not uploaded to the back office. Their size is bounded. Android backups/device transfer are excluded to avoid transferring installation credentials and private browsing state between devices. Website cookie/storage protection also depends on Android and WebView.

## Compatibility scope

The current minimum is Android 8 (API 26), with compile/target API 35. This is an explicit supported range, not a promise that every Android phone or every historical Android version works.

The app checks WebView proxy support before custom DNS mode, uses updated AndroidX WebView APIs, handles system bars/keyboard insets, provides a scrollable header and minimum touch sizes, restores tabs lazily, limits tab count and report queues, watches network changes without replaying website actions, and presents a useful error screen after renderer/connection failures. The APK uses managed Kotlin/Java code and does not bundle native `.so` libraries; device/system WebView compatibility still needs testing.

Google Play services and valid Firebase configuration are required for FCM push. Phones without Google services retain website access and in-app campaigns; Huawei/other vendor push adapters have not been integrated. Old or disabled WebView components can prevent the custom connection mode from working. User-friendly update instructions are shown rather than pretending DNS routing works.

Acceptance must cover Android 8 through the newest supported OS, Samsung/Pixel/Xiaomi/Oppo/Realme and relevant Huawei devices, low-RAM phones, tablets/foldables, portrait/landscape, large text/TalkBack, IPv4/IPv6, Wi-Fi/mobile switching, weak/offline networks and the actual website. Website camera/microphone/geolocation requests remain denied; standard file picking is supported. Blob/data downloads need website-specific work, and ordinary network downloads are limited to 100 MB.

Measured startup/frame/memory profiling, generated Baseline Profiles/Macrobenchmark results, vendor-specific push coverage and a broad device-lab matrix are still pending actual device access. No such measurements or universal compatibility claim have been invented.

## Security boundaries

TLS errors are cancelled, not ignored. The website's TLS is tunneled without decryption; there is no password-capturing JavaScript bridge. Untrusted file/content/javascript/data schemes and embedded credentials are blocked. Local/private network destinations are excluded from the website proxy. Remote configuration is signed and checked for expiry and revision; planned public signing-key rotation supports multiple pinned public keys. Admin changes require authenticated role permissions and 2FA.

Telemetry does not contain page contents, passwords, query strings or account balances. Android Autofill depends on the website/WebView/password manager and does not give the back office access to saved passwords. Root/debug signals are advisory and can be falsified; server permissions are the security boundary. No app can be guaranteed uncrackable.

DNS can help when the failure is DNS resolution. It cannot guarantee bypassing IP blocking, TLS/SNI filtering, website outages or provider restrictions. A backup domain must already serve the website correctly. Required updates block app use according to a signed rule, but Android still asks the user to permit and confirm installation.

## Verification performed

- Backend: **31 tests, 135 assertions passed**, including 2FA/replay/recovery, roles, signed/staged configuration, launch-only deadlines, test campaign isolation, download verification gates, link controls, diagnostics validation/deduplication and revoked tokens.
- Laravel Pint passed. Configuration-form JavaScript passed Node syntax checking. Composer audit reported no dependency advisories at the time of this check.
- Android: **9 JVM unit tests passed** covering external-tab expiry and opening-only display policy.
- Debug app and device-test APK compilation passed. Debug and release Android lint checks passed with no errors; 58 remaining debug warnings concern optional Kotlin extensions, redundant SDK checks, English UI resources and a non-consuming WebView touch listener.
- A temporary-key QA release also passed production R8 shrinking, resource shrinking and APK packaging. This proves the release pipeline builds; it is not an owner-signed production release and is not being distributed.
- On-device encrypted-store instrumentation test sources compile. They have **not been executed** because no emulator or phone was available.
- No physical website/game, password-manager, push, banking/payment or installer acceptance test was possible without the real website and device.

## Preview and release status

The downloadable preview is a debug evaluation build. It uses `https://example.com`; the management API and Firebase are not connected. It does not represent a production Betna service. Preview certificates are temporary and can differ from an earlier preview; removing an earlier preview deletes that preview's local browsing data. Production updates must keep one permanent owner-controlled Android signing certificate and must not require uninstalling the app.

The source is prepared for the owner's private GitHub repository, `tekleabkidus-commits/BetnaApp`. Live Laravel Cloud deployment remains pending the owner's connected hosting setup. Production configuration also needs the actual website URL, Telegram username, final HTTPS API domain, exact package identifier, Firebase setup and securely held signing keys. Do not paste private signing keys or service-account secrets into public source or reports.

The source README contains local setup, release verification and Laravel Cloud deployment instructions. Cloud needs a database, queue worker, scheduler, shared cache/sessions and durable public object storage for APKs.

## Official implementation references

- [Android WebView state management](https://developer.android.com/develop/ui/views/layout/webapps/manage-webview-state)
- [WebView saveState and restoreState API](https://developer.android.com/reference/android/webkit/WebView)
- [Android backup and device-transfer rules](https://developer.android.com/identity/data/autobackup)
- [Android core app quality](https://developer.android.com/docs/quality-guidelines/core-app-quality)
- [Firebase Android client setup](https://firebase.google.com/docs/cloud-messaging/android/client)
- [Android release optimization with R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
- [OWASP Mobile Application Security](https://mas.owasp.org/)
