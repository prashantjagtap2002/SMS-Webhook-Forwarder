# SMS Webhook Forwarder

Standalone Android Studio project that listens for incoming SMS and forwards each message to a configurable webhook as JSON.

## What it does

- Requests `RECEIVE_SMS` at runtime.
- Declares `INTERNET` and `ACCESS_NETWORK_STATE` in the manifest.
- Receives `android.provider.Telephony.SMS_RECEIVED`.
- Extracts the sender and message body.
- Queues a background `WorkManager` job.
- Uses `OkHttp` to `POST` JSON to the saved webhook URL.
- Retries after transient network failures and retryable HTTP responses.

## Payload

```json
{
  "sender": "+15551234567",
  "message": "Your OTP is 123456",
  "receivedAt": "2026-04-12T10:30:00Z",
  "receivedAtMillis": 1775989800000,
  "deviceModel": "Google Pixel 9"
}
```

## Notes

- Android grants `INTERNET` automatically when it is declared in the manifest, so there is no runtime dialog for it.
- Android does not expose native RCS messages through `SMS_RECEIVED`, so this project forwards SMS only.
