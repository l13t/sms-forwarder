# SMS Forwarder

A personal Android app (Android 10+, API 29) that forwards incoming SMS, completed calls, and notifications from selected apps directly to n8n or another HTTP webhook. The app supports English and Ukrainian and needs no intermediary server.

## Features

- Independent SMS, call, and notification switches, plus a global pause.
- Multiple HTTP/HTTPS webhooks with custom headers and test requests.
- Rules with nested AND/OR groups, sender/number, SIM/account ID, package name, text, regular expressions, and schedules.
- Missed, answered incoming, outgoing, and rejected calls after they appear in the phone's call log.
- A persistent Room queue, separate deliveries per endpoint, retry backoff, manual retries, and delivery history.
- A foreground service with WorkManager recovery jobs. The queue survives process restarts and device reboots.
- A language selector: system language, English, or Ukrainian. Unsupported system languages fall back to English.

## Build

Requirements: JDK 17, Android SDK Platform 36, Build Tools 35.0.0, and internet access for the initial Gradle and Maven dependency downloads. Dependency versions are pinned in Gradle.

1. Open this directory in Android Studio and install the suggested SDK components, or set `ANDROID_HOME` to your SDK directory.
2. Run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. To install it from a connected computer:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK uses your local debug signing key. For regular use, follow the [signed APK release guide](docs/releases.md) to configure a permanent signing key and publish installable APKs through GitHub Actions. Do not commit signing keys or `local.properties`.

The GitHub Actions workflow **Android checks and APK** runs unit tests, lint, and the APK build, then uploads the APK as an artifact. Run it in your GitHub repository; no remote build has been triggered from this workspace.

SDK setup explicitly installs `platform-tools`, `platforms;android-36`, and `build-tools;35.0.0`. It skips the obsolete `tools` package and the global SDK license sweep; only licenses requested while installing these packages are accepted.

Every successful push or merge build on `master` automatically publishes a signed APK and checksum to GitHub Releases after both debug and release checks pass. Versions and tags are generated automatically as `1.0.<CI run number>` and `v1.0.<CI run number>`. Configure signing secrets once using `bash tools/setup_release_signing.sh l13t/sms-forwarder`; subsequent releases require only a normal push. See the [release guide](docs/releases.md) for setup, backups, and installation.

## Initial setup

The instructions below use English UI labels. On **Status → App language**, choose **System language**, **English**, or **Ukrainian**. The selection is saved for future launches and applies to screens, validation messages, delivery diagnostics, and the service notification. Notification content and other captured event data retain their original language; API identifiers stay consistent across locales. Android-owned permission screens follow the system language.

1. Add a webhook with a name, URL, and JSON headers, such as `{"X-Webhook-Token":"your-token"}`.
2. Create a rule with event types, conditions, and one or more destination webhooks. An empty AND group matches all events of the selected types.
3. On the **Status** screen, enable the required sources and grant their permissions. For notifications, also select the source apps.
4. Enable **Run in background**, allow the service notification, and allow unrestricted battery use. Some manufacturers also require an autostart permission.
5. Tap **Test** next to the webhook and inspect the delivery history. This sends a real POST request and bypasses rules, but both forwarding and the webhook must be enabled.

Android may block notification access for a sideloaded APK. Open the app's system information page, select the three-dot menu, choose **Allow restricted settings**, and then grant notification access. The available settings depend on the OS and installer.

After a reboot, forwarding resumes after the first unlock. After **Force stop**, open the app and enable forwarding or start the service again. Diagnostics indicate when the service is not running.

## Rules and delivery

Phone numbers are compared as strings without format normalization. EQUALS and CONTAINS are case-insensitive; regular expressions are case-sensitive unless modified with `(?i)`. Groups support up to eight nesting levels. An empty AND group is true; an empty OR group is false.

Schedules filter by **event time**, using the phone's current time zone. The start is inclusive and the end is exclusive; equal start and end times mean a full day. An overnight interval belongs to its starting day: Monday 22:00–06:00 includes Tuesday before 06:00. Events outside the schedule are discarded for that rule.

All matching rules apply. One event creates at most one delivery per webhook ID; separate webhook entries with the same URL remain separate destinations. Each queued delivery keeps a snapshot of its URL, headers, and payload. Editing a webhook affects new deliveries. Pausing a webhook suspends its pending deliveries and excludes it from new deliveries; deleting it cancels its unfinished deliveries. A request already in progress may finish after a pause or deletion. Deleting a rule does not cancel existing deliveries.

`2xx` means success. Network failures, `408`, `429`, and `5xx` trigger retries with exponential backoff from 30 seconds to six hours, respecting `Retry-After`, for up to seven days. Other responses, including `3xx`, require manual correction. Redirects are not followed. A manual retry opens a new seven-day retry window while preserving identifiers and the original request. Create a new test or incoming event to use an updated URL or token.

Delivery can repeat when the response is lost: deduplicate using `delivery_id`. An HTTP success response confirms endpoint acceptance, not completion of the entire n8n workflow. Event ordering across webhooks is not guaranteed.

Completed deliveries are kept for seven days after completion. All deliveries expire 30 days after creation. Clearing history removes completed records only. Event content is stored in the app's private database, and headers are encrypted using Android Keystore. Backup and private-data transfer are disabled. HTTP sends data without TLS and displays a warning in the app. HTTPS accepts certificates trusted by the system.

## Android limitations

- The app receives new incoming SMS; it does not read the entire message history. SMS containing an SMS Retriever hash may be delivered only to the intended app.
- Enabling call collection for the first time, re-enabling it, or resuming after a global pause establishes a baseline at the current call log position. Earlier calls are not forwarded. Process recovery and device reboots resume from the saved position.
- SIM/account IDs come from the OS and do not necessarily mean “SIM 1” or “SIM 2.” IDs can differ between calls and SMS. Inspect delivery JSON before creating SIM filters.
- Notification content depends on what Android exposes; Android 15+ may redact OTPs. The app ignores its own notifications and group summaries. Repeated posts with unchanged title/text for the same active notification are ignored; changed text creates a new event.
- The app picker lists apps with launcher activities. Other apps, including system apps, can be added by package name.
- Collection and delivery may be unavailable before the first unlock, after Force stop, with revoked permissions, or under manufacturer restrictions. Events the OS did not deliver to the app cannot be reliably recovered. Immediate delivery during Doze is not guaranteed.
- MMS, attachments, call recording, sending SMS, and remote commands are not implemented. Google Play publishing is outside this version's scope.

## Documentation and checks

- [Webhook API and n8n setup](docs/webhook.md)
- [Importable n8n workflow](docs/n8n-workflow.json)
- [Android verification checklist](docs/testing.md)
- [Signed APK releases](docs/releases.md)

Run the full JVM suite with `./gradlew testDebugUnitTest`. Run Room/queue instrumentation tests with `./gradlew connectedDebugAndroidTest` and a connected emulator or phone.

Without SDK or network access, `python3 tools/check_offline.py` can use a cached Kotlin compiler and JUnit from Gradle, or the directory specified by `KOTLIN_COMPILER_LIB`. It checks Kotlin syntax and domain logic with temporary stand-ins for **serialization annotations/configuration only**. It does not validate Android APIs, Compose, Room code generation, JSON serialization, or the HTTP client. `python3 tools/check_storage.py` exercises the queue's actual DAO SQL using SQLite.

Official references: [SMS reception](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents), [Android 15 OTP redaction](https://developer.android.com/about/versions/15/behavior-changes-all), and [specialUse foreground services](https://developer.android.com/about/versions/14/changes/fgs-types-required).
