# Monolith

[![GitHub release](https://img.shields.io/github/v/release/arthgirard/monolith)](https://github.com/arthgirard/monolith/releases/latest)

An NFC tag as a physical switch for the apps you keep opening out of habit. Tap the tag and
your chosen apps get blocked; tap it again and they're back. There's no in-app toggle for this
by design: the tag is the only quick way out, aside from a timed emergency bypass for when the
tag isn't around.

## Features

- **Tag-only control.** No in-app switch to turn blocking off. The NFC tag is the only fast way
  out, so the friction of getting up and finding it is the point.
- **Real enforcement.** An Accessibility Service and a Notification Listener work together to
  block the app screen and cancel notifications, not just hide an icon.
- **Emergency bypass.** A 15-minute countdown you can trigger from the home screen when the tag
  isn't around, limited to one per cycle so it can't be leaned on as a workaround.
- **Important people.** Allowlist specific names or handles per app so notifications from people
  who matter still get through even while that app is blocked.
- **Time saved stats.** A day/week/month/year breakdown of how much time Monolith actually spent
  enforcing, so you can see the habit changing instead of just trusting it is.
- **In-app updates.** Since Monolith isn't on the Play Store, it checks GitHub releases on demand
  and can download and hand the APK straight to the system installer.

## How it works

Pick the apps you want blocked from your installed apps list, then link an NFC tag to your
phone. Monolith tries to write an NDEF record to the tag (a `monolith://tag/<uid>` URI, so a tap
launches the app directly instead of going through a browser). Read-only or unformattable tags
fall back to matching on the tag's hardware UID instead. Either way, the tap is recognized as
yours from then on.

Once linked, tapping the tag flips Monolith on or off. The app list locks while it's on, so
the tag stays the only way to change what's blocked. Enforcement itself runs on two independent
services: an Accessibility Service watches for foreground app changes and throws up a
full-screen block when a listed app (or Settings, so you can't disable the service to escape)
comes to the front, and a Notification Listener cancels notifications from blocked apps so they
can't reach you through the shade either.

Notifications from people you've marked as important still get through. You add them per app
by name or handle, and matching is done against the notification's title and text. Editing that
list is locked the same way the app list is: only while Monolith is off.

Lost the tag, or it's not on you? The home screen has a 15-minute countdown bypass that lifts
Monolith temporarily, one per Monolith cycle, so it can't be spammed. Tapping the tag again,
either on or off, resets that allowance. Time spent in bypass doesn't count toward your saved
time.

Every session Monolith spends actively blocking is logged, and the time saved screen breaks
that down by day, week, month, or year so you can see the pattern over time.

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

Standard Gradle Android project, open in Android Studio or run:

```
./gradlew assembleDebug
```

(Add the Gradle wrapper jar via Android Studio's "Sync Project" on first open, or run
`gradle wrapper` once if you have Gradle installed locally.)
