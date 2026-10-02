# Betna — Android and Laravel back office

Betna 0.4.0 for an existing website you cannot modify. `backend/` is Laravel 13 (PHP 8.4+); `android/` is Kotlin (Android 8+). Laravel Cloud hosts the admin/API.

## Included

- Website shell with Home, Reload, Tabs and Close. Public external domains require no approval list. External tabs silently expire after 60 minutes, including the selected tab; back office controls duration, opening/activity basis, enablement and tab limit. Main tab never expires.
- DNS-over-HTTPS resolvers, bootstrap addresses, host routing rules and optional system fallback. A loopback CONNECT proxy routes WebView traffic without decrypting website TLS. Local/private destinations are excluded.
- Native error screen, Retry, connection checks with support references, and explicit backup-homepage navigation. Startup and page-loading spinners plus page progress.
- Browser-like session handling: current WebViews stay open when returning, cookies and local storage are retained, and tabs/history are saved encrypted on the device. Back from the homepage backgrounds the app. No settings refresh reloads a game.
- Editable Telegram username, public Download Betna App page, stable APK redirect and managed share links with request counters.
- Normal DNS/settings forms, selected test-device configuration revisions, promotion to everyone, rollback and optional/blocking maintenance notices.
- Optional local encrypted password vault with device authentication, save prompts, account management and explicit confirmation before filling a previously saved Betna login on a new configured Betna domain. Passwords never reach Laravel or telemetry. Android password-manager autofill remains an alternative; form compatibility depends on the provider website.
- Compact icon header, rounded native popups, stable loading indicators and in-place rotation/resize handling. Fullscreen website media is supported.
- Foreground, permission-based approximate location reporting, filtered clustered location map and audited exports. Per-device pages show activity, location history and command status. Website login attempts are reported separately from successful website logins, which require provider integration.
- Standard WebView caching and signed, targeted temporary-cache clearing commands (immediate or next opening), with acknowledgements and expiry. Cookies, saved credentials and navigation history are preserved.
- Admin-controlled per-app WireGuard VPN, Android consent, mandatory-connection blocking, primary/backup endpoints, peer enrollment, traffic reports and configurable cost estimates. VPN remains off until real servers and peers are provisioned.
- Manual/scheduled/recurring push and event-triggered popup/banner campaigns, audience filters, language variants, caps, quiet hours and opt-outs. Preview, selected-device test delivery and explicit promotion; an opening-only trigger is limited to a configurable 2–30 second launch window.
- Required authenticator two-factor setup, one-use recovery codes and installation-token revocation. Compatibility metadata includes Android, phone model/manufacturer, WebView, push availability and low-memory devices; startup, crash/renderer and UI-stall telemetry stays bounded.
- Installation, approximate online, versions, events, campaign delivery and retention reports; CSV export; owner/operator/viewer roles and audit logs.
- APK drafts/uploads, artifact verification, staged rollout, required minimum version and optional reminders. Android installation consent remains required.
- Signed remote settings, cached configuration, Keystore-protected installation token and bounded offline telemetry.

## Build status

Unconfigured source builds use preview defaults. The delivered signed Betna 0.4.0 APK connects to `https://betnaapp-production-ekfmlk.laravel.cloud/` using the existing public configuration pin and owner signing certificate. Private build settings and keys are excluded from this repository. Firebase delivery requires matching Android and server credentials. No physical-device acceptance test has been performed. DNS cannot bypass all blocking methods; backup domains must already serve the website. The native viewport fills available space, but provider-specific footer spacing requires verification on the actual website and phone.

## Local backend

```sh
cd backend
composer install
cp .env.example .env
php artisan key:generate
touch database/database.sqlite
php artisan migrate
php artisan app:admin owner@example.com
php artisan mobile:signing-key
php artisan storage:link
php artisan serve
```

The key command outputs a PRIVATE server key and PUBLIC Android pin. Store the private value only in server secrets as `MOBILE_SIGNING_PRIVATE_KEY`; build Android with the public `CONFIG_PUBLIC_KEY`. Never commit private key output. Set `MOBILE_WEBSITE_URL` as the initial homepage. Complete authenticator setup on the first admin login, then open Configuration for the website/DNS settings and Launch & links for the Telegram username. No default admin password exists. Keep `MOBILE_REQUIRE_TWO_FACTOR=true` in production.

Run `php artisan queue:work --tries=5 --timeout=60` and `php artisan schedule:work` in separate terminals. Supply `FIREBASE_CREDENTIALS_JSON` through server secrets for push, never inside the APK.

Verification: `php artisan test` and `vendor/bin/pint --test`.

## Laravel Cloud

1. Put the source in your private Git repository, connect Cloud, set root directory `backend` and PHP 8.4 or a compatible newer runtime.
2. Attach PostgreSQL, shared database cache/sessions (or Redis), and a durable public object-storage bucket under disk name `public`. Set its public URL as required by the attachment. Uploads intentionally omit per-object ACLs. Local Cloud disk is ephemeral.
3. Set `APP_ENV=production`, `APP_DEBUG=false`, HTTPS `APP_URL`, `APP_KEY`, `SESSION_SECURE_COOKIE=true`, `SESSION_ENCRYPT=true`, `LOG_LEVEL=warning`, `DB_CONNECTION=pgsql`, `QUEUE_CONNECTION=database`, `CACHE_STORE=database`, `SESSION_DRIVER=database`, database credentials and mobile/Firebase secrets.
4. Build: `composer install --no-dev --no-interaction --prefer-dist --optimize-autoloader && php artisan optimize`.
5. Deploy: `php artisan migrate --force`.
6. Enable scheduler and worker `php artisan queue:work --tries=5 --timeout=60`. Shared cache provides single-server schedule locks.
7. Create owner, configure HTTPS domain, publish settings and rebuild Android against that API.

Source repository: https://github.com/tekleabkidus-commits/BetnaApp. For upgrades, deploy this commit with `php artisan migrate --force`; the migration adds browser-control tables without clearing existing sessions or installations. Confirm the scheduler and queue worker remain enabled. Source publication does not itself verify Cloud deployment.

## Android build

Use JDK 17, Android SDK 35 and included Gradle wrapper. Configure SDK path in `android/local.properties`, which must not be committed. These settings accept Gradle properties or environment variables:

| Setting | Purpose |
| --- | --- |
| APP_ID, APP_NAME | Permanent package identifier and display name |
| WEBSITE_URL | Actual HTTPS homepage |
| API_URLS | Comma-separated trusted HTTPS base URLs, without `/api/v1` |
| CONFIG_PUBLIC_KEY | Base64 DER public signing key; multiple comma-separated public pins support a planned key rotation |
| FIREBASE_APPLICATION_ID, FIREBASE_API_KEY, FIREBASE_PROJECT_ID, FIREBASE_SENDER_ID | Android Firebase configuration matching exact package ID |
| VERSION_CODE, VERSION_NAME | Increasing integer and visible version |
| RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD | Owner signing key via local/CI secrets |

```sh
cd android
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
# With a connected device or emulator:
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
```

Release builds require actual website, HTTPS API, configuration pin and owner signing key. Preserve the same Android signing certificate for future updates. Back up both signing keys securely; they have different purposes. Publish the signed production APK on your HTTPS website.

Add a release draft, then verify the hosted APK in a trusted environment with production DB access and Android SDK tools:

```sh
php artisan releases:verify RELEASE_ID --package=YOUR.APP.ID --certificate=EXPECTED_SHA256_CERTIFICATE --apksigner=/path/to/apksigner --aapt=/path/to/aapt2
```

Cloud PHP does not normally include Android tools; provision the verification environment first. Publish only after verification. Android independently checks size, hash, package, version and certificate before opening the installer.

## Acceptance and boundaries

- Test actual website login/autofill, redirects, cookies, uploads, downloads and payment/banking/Telegram handoffs on real Android devices and the affected network.
- Test Firebase delivery with notification permission granted/denied and app backgrounded/terminated. Delivery/online status cannot prove a person read a message.
- Quiet hours suppress eligibility at the scheduled run; a one-time push is not deferred automatically. Recurrence is elapsed minutes, not a calendar/DST schedule. Banners currently use top-position native dialogs.
- Tabs and WebView navigation history are restored after process death where WebView supports it. Runtime JavaScript/game state is not a durable snapshot: Android memory pressure, renderer termination, network loss and website session expiry can interrupt a game. Cookies are never deliberately cleared. External-tab expiry still applies after restoration. Website camera/microphone/geolocation requests are denied in this version. Standard file picking is supported. Blob/data downloads require site-specific support; network downloads have a 100 MB limit.
- Android 8 is the minimum. Updated Android System WebView with proxy-override support is required for the custom-DNS mode. FCM requires available Google Play services and valid Firebase settings; other devices still have the website and in-app campaigns but no vendor push integration.
- Device instrumentation tests are included, but no emulator or phone was available to run them. Phone/network coverage, full WebView game restoration checks and measured startup/memory profiling remain release acceptance work. R8 makes release code harder to inspect; no client app can be guaranteed uncrackable.
- Test required/optional updates, invalid certificate, installation permission, offline cache and configuration expiry before public release. Updates cannot install silently.
- Reports count installations, not verified people. Website accounts, balances and transactions need site cooperation. Detailed events expire after 90 days; installation/delivery records remain.
- Confirm support details, production accessibility and device acceptance before public rollout. Never send passwords, page contents or query strings to telemetry.
- VPN setup: create WireGuard server interfaces with the configured public endpoints, enable forwarding/NAT and routes for `10.66.0.0/16`, then install each enrolled device public key with its assigned `/32` address on every primary/backup server. Mark the peer provisioned only after this succeeds. Server private keys stay on servers; device private keys stay on devices. Configure and test endpoints with a test-device revision before enabling mandatory VPN for everyone. Traffic estimates are diagnostics, not provider billing or verified capacity.
- Location requires Android consent, is collected only while the app is foregrounded, and respects the signed reporting interval. The location map displays last observed coordinates and their accuracy; online counts use recent heartbeats. Retention is configurable (default 90 days).

## Public downloads and support

`/download` is the public installation page; `/download/apk` points to the chosen verified, published release; `/support/telegram` redirects to the current Telegram contact. Configure these through Launch & links. Named `/get/{slug}` links can point to the download page or APK and can be disabled. Request counters do not prove unique users or completed installations.

Download publication starts disabled until an APK is verified. Automatic selection uses a release rolled out to 100%; deliberately selecting a verified release overrides that choice. Preview APKs are for evaluation and may require removing an older preview if its debug certificate differs. Production releases must use one permanent owner-controlled signing certificate.
