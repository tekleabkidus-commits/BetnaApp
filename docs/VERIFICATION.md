# Betna 0.6.0 implementation and verification report

Updated 4 October 2026. Betna uses Kotlin/WebView on Android 8+ and Laravel 13/PHP 8.4+ on the back office. The provider website is not modified.

## Changes and their purpose

| Change | Why it was added |
| --- | --- |
| Bottom browser toolbar | Back, Home, Reload, Tabs and More stay below the website. The tab chooser is hidden when only one tab is open. Native content fills the remaining viewport. |
| App-owned dialogs and cards | Rounded sheets, inline banners, popups, update notices, permission explanations and toast cards share light/dark colors, typography and usable touch targets. Android consent and installation dialogs remain controlled by Android. |
| Live rotation and resizing | The same WebViews remain attached across orientation, window size, font scale and appearance changes. Changing layout does not deliberately reload a game. |
| Renderer recovery | A failed renderer is detached and destroyed without querying its dead WebView. The app presents Retry and support without automatically repeating a website action. |
| Safe lifecycle handling | Delayed callbacks check Activity lifetime, background writers survive late close callbacks, telemetry disk failures do not crash the UI, and optional Firebase/VPN startup failures have controlled handling. |
| Required updates and maintenance | Blocking screens retain the Activity and tabs instead of finishing the app. Declining installation permission does not repeatedly reopen Android Settings. |
| Password capture and fallback | Login and registration forms, SPA buttons, confirmation fields and password visibility toggles are supported. Capture is local; Save current login and manual entry provide fallbacks for provider-specific forms. |
| Local credential protection | Existing vault format and Keystore alias are retained. Saving, reading, filling and revealing require device authentication; credential dialogs prevent screenshots. Changed configured Betna domains require explicit confirmation before filling an old credential. |
| Permission reminders | Initial setup offers location and notifications. After an inferred completed login form, reminders alternate, occur at most once a day and at least 48 hours apart for each permission, stop while permission is granted, and offer Settings when Android will no longer prompt. |
| Device stability reporting | Safe crash codes, previous Android process-exit reasons, renderer failures and UI stalls help distinguish app errors, low memory, user stops and permission changes. Reports never include passwords, page contents or query strings. |
| SuperAdmin | This role can access other admin pages and actions but cannot access Connection & tabs, App releases, Betna VPN, Cache controls, Connection reports, Activity log or Staff access. The server enforces exclusions as well as hiding navigation. Owner access remains intact. |
| Release checks | Backend tests, password-script tests, Android unit/build/lint checks and Android 8/15 emulator tests run in GitHub. Production compilation uses only public API/site/pin settings; final owner signing happens separately. |

Existing DNS settings, test-device revisions, Telegram support, managed download/share links, campaigns, location maps, VPN controls, temporary-cache commands, two-factor authentication and reports are retained.

## Sessions and credentials

Cookies and web storage are not deliberately cleared by resume, rotation, configuration refresh or temporary-cache commands. While Android keeps the process alive, the same live WebViews and JavaScript state are retained. Browser history snapshots are encrypted locally and never uploaded. External tabs still follow the remotely configured silent-expiry rule; the main tab never expires.

Android may terminate an app or renderer for memory pressure, updates, revoked permissions or a user stop. Saved history is not a durable snapshot of JavaScript or a running game. After process death, pages may load again. Website server-side session expiry cannot be overridden without website cooperation. The app does not automatically resubmit POST forms.

Password capture is restricted to HTTPS origins explicitly configured as the Betna homepage or backups, and validates the sending frame against the active top-level origin. Credentials stay on the device. Unrelated external pages are browsable without gaining access to the private vault. Login reports describe an observed form attempt/resolution, not a provider-verified successful account login. No website usernames or passwords are sent to Laravel.

A compatible, updated Android System WebView is required for message-based private-vault integration and custom-DNS proxy mode. Android password-manager Autofill is available as an alternative. Provider-specific acceptance remains necessary; custom canvas login screens cannot be assumed to expose ordinary fields.

## Verification

- Backend: **57 tests, 382 assertions passed**, including excluded routes/actions, permitted SuperAdmin routes, role creation, owner access, stability-report validation, installation scoping and rejection of credential fields.
- Laravel Pint passed. Existing backend functionality is covered by the full suite.
- Password-capture script: **7 Node tests passed**, including login/registration capture, visibility toggles, source isolation and native-setter fill behavior.
- Android JVM tests cover external-tab expiry, opening-only campaigns and alternating permission reminders.
- GitHub compiles debug/device-test and optimized release APKs and runs debug/release lint.
- Device tests exercise encrypted history, rotation/background retention, deliberate renderer failure, update/maintenance flows, password capture where the WebView supports it, and native UI screenshots on Android 8 and Android 15.
- The previous production signing certificate has been recovered for update compatibility. Final APK signature, package and version must be checked before delivery.

The emulator suite uses an offline website fixture and debug-only isolated app storage. It does not prove the actual provider website, betting/game resumption, Firebase delivery, payment apps, installation consent, or location behavior on the user's physical phone. The user's original exit cannot be definitively attributed without that device's logs or new stability report.

## Deployment and release boundaries

SuperAdmin is selectable by the owner in Staff access; no new real account is created automatically. No new migration is required for this role. Existing database/session records are preserved.

The unsigned GitHub distribution artifact is a build intermediate, not an installable finished app. Production updates must preserve package `com.appcontrol.mobile`, the owner signing certificate, and increasing version codes. Installing over the existing app preserves local data; uninstalling erases it.

GitHub publication is separate from verified Laravel Cloud deployment. The environment needs its existing database, queue worker, scheduler, signed configuration, Telegram settings, durable release storage and valid Firebase credentials for push. VPN needs provisioned WireGuard servers/peers and Android consent. Client configuration contains public pins, never server or owner private keys.

R8, encrypted storage, signed configuration, checked update identity, TLS validation, two-factor authentication and server permissions improve security. No client app can be promised uncrackable or unable to close under every Android condition.

## Primary implementation references

- [Android WebView state management](https://developer.android.com/develop/ui/views/layout/webapps/manage-webview-state)
- [WebView renderer recovery](https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,android.webkit.RenderProcessGoneDetail))
- [WebViewCompat message and document-start APIs](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
- [Android application exit reasons](https://developer.android.com/reference/android/app/ApplicationExitInfo)
- [Android notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Android test runner](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)
- [Android release optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
