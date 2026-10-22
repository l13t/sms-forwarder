# Verification

## Automated checks

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
python3 tools/check_offline.py
python3 tools/check_storage.py
```

The first two commands require the Android SDK; the second also requires a connected device. The offline runner uses a cached Kotlin compiler for domain logic and syntax checks and does not replace an Android build. JVM, instrumentation, and lint reports are written to `app/build/reports`.

## Android 10 and current stable Android emulators

- Test a clean install, enable each source independently, deny permissions, and request them again. Missing permissions must produce useful diagnostics.
- Add one webhook, then a second. Two matching rules targeting the same webhook produce one delivery; rules targeting different webhooks produce separate deliveries.
- Confirm SMS, call, and notification events follow their rules. Global pause stops new collection and queue delivery.
- A test event bypasses rules and appears in n8n. Testing a paused webhook must not create a delivery.
- HTTP works with a warning. HTTPS with an untrusted certificate is rejected. Redirects are not followed.
- `204` completes delivery; `401` produces an error; `429` with `Retry-After` and `503` schedule retries. Error messages do not expose URLs or tokens.
- Disable the network, receive an event, kill the process without Force stop, and restore connectivity. Delivery must resume with the same `delivery_id`.
- Pausing a webhook preserves its pending queue. Deleting a webhook cancels unfinished deliveries. An in-flight request may still finish.
- Reboot and unlock once: an enabled service and its queue must resume. Verify manual recovery after Force stop.
- Invalid regular expressions and schedule times cannot be saved. Test overnight schedules and nested AND/OR groups in the rule editor without sending HTTP requests.
- Switch between English, Ukrainian, and the system language. Check navigation, editors, validation, diagnostics, delivery history, and the service notification. Verify persistence after restarting the process and fallback to English for unsupported system languages.

## Physical phone

- Receive real short and multipart SMS with Unicode content and alphanumeric senders.
- On dual-SIM devices, inspect `sim_id` for SMS and calls, then filter using the actual identifiers.
- Exercise answered incoming, outgoing, missed, rejected, and withheld-number calls. Existing call history must not be forwarded when collection is first enabled.
- Grant notification access after sideloading. Test system and third-party apps, unchanged updates, text changes, group summaries, and the app's own service notification.
- On Android 15+, record OS OTP redaction as a platform limitation rather than a failed HTTP delivery.
- Test a locked screen, extended idle/Doze, Wi-Fi to mobile-data changes, unrestricted battery settings, and manufacturer-specific autostart settings.

## Acceptance scenario

Create three rules: SMS to webhook A; missed calls to webhook B; selected app notifications to both A and B. Generate each event and inspect routing, payloads, and history. Repeat without network connectivity, restart the process, and restore connectivity. Repeated requests with the same `delivery_id` must not produce duplicate business effects in the destination workflow.

## Verification record (2026-10-22)

Initial implementation checks completed: 14 rule/backoff JVM tests using the offline runner, eight SQLite checks against actual DAO queries, syntax analysis of all Kotlin files, XML parsing, workflow structure checks, and execution of the envelope-validation JavaScript with valid and invalid input. The language update additionally passed six JVM tests covering language resolution, switching, format arguments across every translation, and rendering historical diagnostics in the selected language (20 JVM tests total). Language preference persistence also has an instrumentation test, which has not been run here.

Not completed in this workspace: a full Gradle/Android compilation, lint, real JSON serialization and HTTP-client tests, Room instrumentation, device/UI execution, or import into a live n8n instance. The Android SDK is absent, and downloading Gradle 8.13 failed with `UnknownHostException: services.gradle.org` because terminal networking is unavailable. No APK has been generated here. Complete verification in an environment with SDK and network access, then run the device scenarios above.
