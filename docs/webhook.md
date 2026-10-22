# Webhook API v1 and n8n

Each delivery is an HTTP POST with `Content-Type: application/json; charset=utf-8` and `Idempotency-Key: <delivery_id>`. Custom headers are included in every attempt. `Content-Type`, `Content-Length`, `Host`, `Connection`, `Transfer-Encoding`, and `Idempotency-Key` are reserved. URL credentials and fragments are rejected.

```json
{
  "schema_version": 1,
  "event_id": "8e9ceffe-eecb-48a7-81c9-a2e5fba61d16",
  "delivery_id": "6736da79-122f-40b4-bd32-bb171bf84ef6",
  "device_id": "206e704a-96be-4fb2-afba-b76a121d546a",
  "device_name": "Pixel",
  "occurred_at": "2026-10-22T10:30:00Z",
  "type": "sms.received",
  "data": {
    "sender": "Bank",
    "number": null,
    "text": "Your code is 482913",
    "sim_id": "2",
    "call_type": null,
    "duration_seconds": null,
    "package_name": null,
    "app_name": null,
    "title": null,
    "notification_key": null
  }
}
```

Common envelope fields are always present. `event_id` identifies the same event across destination webhooks. `delivery_id` identifies a delivery to one webhook and remains unchanged during automatic or manual retries. `device_id` is a random installation UUID, not a phone number. `occurred_at` is UTC ISO 8601; for calls, it is the call's start time from the call log, although the event is captured after the call ends.

| Type | Data |
| --- | --- |
| `sms.received` | `sender`, `text`, `sim_id` |
| `call.completed` | `number`, `call_type`: `missed` / `incoming` / `outgoing` / `rejected`, `duration_seconds`, `sim_id` |
| `notification.posted` | `package_name`, `app_name`, `title`, `text`, `notification_key` |
| `test` | `text` containing a test message |

Unavailable or inapplicable fields are `null`. Withheld caller numbers may be unavailable. `sim_id` is a string when the OS provides an identifier. The envelope schema, field names, and event identifiers are independent of the app's display language. User-generated text is forwarded unchanged; the generated test message follows the selected language.

## n8n setup

1. Import `n8n-workflow.json` using the workflow import menu.
2. Configure a **Header Auth** credential on the Webhook node, such as `X-Webhook-Token` with your own token.
3. Add the same header in the app: `{"X-Webhook-Token":"your-token"}`.
4. For the initial test, select **Listen for test event** and use the Test URL. For continuous operation, publish/activate the workflow and switch the app to the Production URL. Existing queued deliveries retain their original URL.
5. Enable forwarding on the phone and tap **Test**. The app's history should show **Delivered**, and n8n should receive `type: test`.
6. Connect your logic after **Process event here**. Event fields are available directly in `$json` after **Validate envelope**.

The template immediately responds with `202`, unwraps and validates the envelope, and filters repeated `delivery_id` values using Remove Duplicates with a history of 10,000 keys. This acknowledges webhook acceptance, not completion of subsequent steps. Critical operations need durable event storage, workflow error recovery, and a unique key in the destination datastore. Built-in deduplication history is limited and can be cleared. Verify import and execution against your n8n version.

Remove Duplicates configuration reference: [official n8n example](https://github.com/n8n-io/skills/blob/main/skills/n8n-data-tables-official/references/DEDUP_PATTERNS.md).
