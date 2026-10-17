# Medication Reminder Assistant

> An Android medication reminder app with alarm notifications, TTS voice broadcast, prescription OCR, and a calendar-based dosage adherence tracker.

A native Android (Java) app that helps users take medication on time. Snap a photo of a prescription to add medications automatically, and get reminded via a full-screen alarm or a high-priority notification, complete with voice broadcast and a calendar-based adherence tracker.

## Features

- **Prescription OCR**: Capture or pick a prescription photo and the app parses medication names, dosages, and schedules using ML Kit Chinese OCR plus a built-in offline drug dictionary with edit-distance fuzzy matching — no network required
- **Dual reminder modes**
  - Alarm mode: Exact triggering via `setAlarmClock`, a foreground service that keeps ringing with vibration and voice broadcast, and an auto-launched full-screen alert page (works on the lock screen)
  - Notification mode: Silent high-priority notification for quiet reminders
- **Full-screen alert page**: Shows all medications due at the same time, with one-tap actions — Taken / Snooze 5 min / Stop ringing; simultaneous reminders are merged into a single page with continuous voice broadcast
- **Notification quick actions**: Act directly from the notification shade without opening the app
- **Voice broadcast**: Reads out each medication's name, dosage, and instructions via system TTS (Chinese)
- **Configurable ring duration**: 1 / 5 / 10 / 15 minutes; ringing stops automatically on timeout and the snooze re-reminder takes effect
- **Adherence calendar**: Records doses cumulatively; days meeting the target show a green circle, while missed days display the actual/expected dose count
- **Reminder reliability**
  - All reminders are re-registered after device reboot or app update
  - Alarms are idempotently rescheduled on every app launch in case the system cleared them
  - Multi-level fallbacks in the ringing chain (system alarm sound → built-in tone) with try-catch at every step
  - First-launch guidance to disable battery optimization, working around aggressive background restrictions on many OEM ROMs
- **Centralized permission management**: The settings page shows real-time status of exact alarms, full-screen intent, Do-Not-Disturb access, and more, with one-tap navigation to grant

## Requirements

- Android 14 (API 34) or higher
- A Chinese TTS engine must be installed on the device for voice broadcast (most devices ship with one; check under "System Settings → Text-to-speech output")

## Permissions

| Permission | Purpose |
| --- | --- |
| Camera | Photograph prescriptions |
| Notifications (POST_NOTIFICATIONS) | Post medication reminders on Android 13+ |
| Exact alarm (USE_EXACT_ALARM) | Medication reminders qualify as alarm-class apps; granted on install |
| Full-screen intent (USE_FULL_SCREEN_INTENT) | Auto-launch the full-screen alert page in alarm mode (separate user toggle on Android 14+) |
| Do-Not-Disturb access (ACCESS_NOTIFICATION_POLICY) | Ring even in DND mode (temporarily lifted while ringing, restored afterwards) |
| Run at startup (RECEIVE_BOOT_COMPLETED) | Restore daily reminders after reboot |
| Ignore battery optimizations | Prevent the system from delaying or killing the alarm process in the background |

## Build

Open the project in Android Studio (Koala or newer), or build from the command line:

```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

The APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

## Project Structure

```
app/src/main/java/com/example/medicationreminderassistant/
├── MainActivity.java            # Medication list, rescheduling on launch, permission prompts
├── CameraActivity.java          # Camera capture (CameraX)
├── ConfirmActivity.java         # Review/edit OCR results
├── DetailActivity.java          # Medication details, editing, adherence calendar
├── SettingsActivity.java        # Reminder mode, ringtone/vibration/TTS, permission management
├── AlarmAlertActivity.java      # Full-screen ringing page (shown on lock screen)
├── adapter/
│   └── MedicationAdapter.java   # Medication list adapter
├── model/
│   ├── Medication.java          # Medication data model
│   └── RecognizeResult.java     # Prescription recognition result model
├── service/
│   ├── AlarmService.java        # Foreground ringing service (sound/vibration/TTS/DND override)
│   ├── NotificationService.java # Alarm scheduling, notification building, rescheduling
│   ├── ReminderReceiver.java    # Trigger on time, handle quick actions
│   ├── BootReceiver.java        # Restore reminders after boot/app update
│   ├── MedicationDatabase.java  # SQLite storage (cumulative dose logging)
│   ├── TTSService.java          # System TTS Chinese broadcast
│   ├── RecognitionService.java  # ML Kit OCR prescription parsing
│   └── DrugDictionary.java      # Offline drug dictionary with fuzzy correction
├── util/
│   ├── SettingsManager.java     # Global settings persistence
│   ├── TimeUtils.java           # Time formatting
│   └── ImageUtils.java          # Image copy/compression
└── widget/
    └── MedicationCalendarView.java # Adherence calendar widget
```

## Tech Stack

- Java 11, AndroidX, Material Components
- CameraX 1.3.4 (capture)
- ML Kit Text Recognition v2, Chinese model (prescription OCR)
- AlarmManager (`setAlarmClock` / `setExactAndAllowWhileIdle` tiered scheduling)
- Foreground service (`specialUse` type) + WakeLock
- SQLite (custom `SQLiteOpenHelper` with incremental migration)
- System `TextToSpeech` (Chinese voice)

## FAQ

**No ringing or no full-screen page when a reminder fires?**
1. Make sure the app was added to the battery optimization whitelist during first-launch guidance — many OEM ROMs kill background alarms otherwise
2. In alarm mode, grant the "Full-screen notifications" permission in the app's settings page (a separate toggle on Android 14+; without it only a heads-up banner is shown)
3. Some devices also require enabling "Auto-start" and "Run in background" for the app in system settings

**No ringing while Do-Not-Disturb is on?**
Grant "Do-Not-Disturb access" in the settings page; the app temporarily lifts DND while ringing and restores it afterwards.

**Voice broadcast is silent?**
Voice broadcast relies on the device's Chinese TTS engine. Check "System Settings → Text-to-speech output" to ensure one is installed and a Chinese voice is selected.

**How to debug the reminder chain?**

```bash
adb logcat -s ReminderReceiver NotificationService AlarmService TTSService
```

## License

For learning and personal use only.
