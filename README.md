# Monolith

Turn an NFC tag into a physical switch for the apps that eat your day. Tap the tag, the
distracting apps disappear; tap it again, they're back. No tag, no unlock — with a 15-minute
emergency bypass for when the tag itself goes missing.

## How it works

- **Link a tag.** Monolith tries to write an NDEF URI (`https://monolith.app/tag/<uid>`) so that
  tapping the tag on a phone without Monolith installed opens the download page. Read-only or
  unformattable tags fall back to matching on the tag's hardware UID instead — either way, the
  tag is now yours.
- **Pick your blocked apps** from the full list of installed apps. The list locks for editing
  while Block Mode is active, so the tag stays the only way out.
- **Tap to toggle.** An Accessibility Service watches foreground app changes; if Block Mode is on
  and the foreground app is on your list (or Settings, to stop you disabling the service),
  Monolith throws up a full-screen block and won't let you through.
- **Lost the tag?** A 15-minute countdown bypass on the Home screen lifts Block Mode temporarily.

## Stack

Kotlin, Jetpack Compose, Material 3, Hilt, DataStore, coroutines/Flow. Clean-ish architecture:
`domain` (models, repository interfaces, use cases) has no Android dependencies beyond the NFC
`Tag` handle; `data` and `nfc` implement those interfaces; `ui` is Compose + ViewModels.

## Building

Standard Gradle Android project — open in Android Studio or run:

```
./gradlew assembleDebug
```

(Add the Gradle wrapper jar via Android Studio's "Sync Project" on first open, or run
`gradle wrapper` once if you have Gradle installed locally.)

## Permissions

Three permissions are requested step-by-step on first launch: Usage Access (to see the
foreground app), Display Over Other Apps (to show the block screen), and Accessibility Service
(to catch app launches in real time). All three are required for blocking to work.
