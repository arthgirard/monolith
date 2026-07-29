# Monolith

[![GitHub release](https://img.shields.io/github/v/release/arthgirard/monolith)](https://github.com/arthgirard/monolith/releases/latest)

An NFC tag as a physical switch for the apps you keep opening out of habit. Tap the tag and
your chosen apps get blocked; tap it again and they're back. There's no in-app toggle for this
by design — the tag is the only quick way out, aside from a timed emergency bypass for when the
tag isn't around.

## How it works

Pick the apps you want blocked from your installed apps list, then link an NFC tag to your
phone. Monolith tries to write an NDEF record to the tag (a `monolith://tag/<uid>` URI, so a tap
launches the app directly instead of going through a browser). Read-only or unformattable tags
fall back to matching on the tag's hardware UID instead — either way, the tap is recognized as
yours from then on.

Once linked, tapping the tag flips Block Mode on or off. The app list locks while it's on, so
the tag stays the only way to change what's blocked. Enforcement itself runs on two independent
services: an Accessibility Service watches for foreground app changes and throws up a
full-screen block when a listed app (or Settings, so you can't disable the service to escape)
comes to the front, and a Notification Listener cancels notifications from blocked apps so they
can't reach you through the shade either.

Lost the tag, or it's not on you? The home screen has a 15-minute countdown bypass that lifts
Block Mode temporarily — one per Block Mode cycle, so it can't be spammed. Tapping the tag again,
either on or off, resets that allowance.

Monolith also checks GitHub releases for newer versions on demand and can download and hand the
APK to the system installer directly, since it isn't distributed through the Play Store.

## Permissions

Four permissions are requested step-by-step during onboarding, and all four are required before
blocking works: Usage Access (to see the foreground app), Display Over Other Apps (to show the
block screen), Accessibility Service (to catch app launches in real time), and Notification
Access (to cancel notifications from blocked apps).

## Stack

Kotlin, Jetpack Compose, Material 3, Hilt, DataStore, coroutines/Flow. `domain` (models,
repository interfaces, use cases) has no Android dependencies beyond the NFC `Tag` handle;
`data` and `nfc` implement those interfaces; `ui` is Compose + ViewModels.

## Building

Standard Gradle Android project — open in Android Studio or run:

```
./gradlew assembleDebug
```

(Add the Gradle wrapper jar via Android Studio's "Sync Project" on first open, or run
`gradle wrapper` once if you have Gradle installed locally.)
