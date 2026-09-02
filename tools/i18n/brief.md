# Monolith translation brief

You are translating the UI copy of Monolith, an Android app that blocks distracting apps
until the user taps a physical NFC tag. The tag is the only fast way back in. That premise is
the whole product, and the copy is written to sound like it: plain, short, a little blunt,
never encouraging or congratulatory.

Translate into the target locale as if the app had been written in that language from the
start. Match the intent and the register, not the word order.

## Voice

- Terse. Most strings are one line. If the English is a fragment, keep a fragment.
- Second person, polite address where the language has a choice (Sie / vous / usted / Lei / u).
  Brazilian Portuguese uses "você", which is already the neutral register there.
  Monolith talks to one person about their own habit, but it never gets familiar with them.
  Prefer impersonal phrasings ("Come si rientra...", "Da concedere una volta") over strained
  polite imperatives. Never use a gendered form to address the reader: "Welcome to Monolith."
  is "Monolith le da la bienvenida.", not "Bienvenido".
- "Tap the tag" is an NFC gesture, not a screen tap. Use the verb the locale uses for holding
  a phone against a tag ("Approchez", "Avvicini", "Aproxime", "Houd ... ertegen"), not the
  word for tapping a button.
- Plain verbs. No marketing register, no "seamless", "effortless", "boost", "empower".
- No exclamation marks, except where the English has one.
- Sentence case for titles and buttons. Never ALL CAPS unless the English is ALL CAPS
  (the widget labels `widget_time_saved_label`, `widget_stat_blocks`, `widget_stat_held` are
  deliberately caps, keep them caps).
- The app never apologises and never cheers. "Bypass ended. Monolith is enforcing again."
  is the tone: a statement of fact.
- Where the English is deliberately hard ("Nothing lifts Monolith but the tag.",
  "You can still uninstall Monolith. That's on purpose."), keep it hard. Do not soften it
  into a polite suggestion. That bluntness is the feature.

## Hard rules

1. **Placeholders are frozen.** `%1$s`, `%2$d`, `%1$d` and friends must appear in the
   translation exactly as in the source, same count, same index. You may reorder them if the
   target grammar needs it; you may not add, drop, or renumber them.
2. **Do-not-translate terms** stay verbatim, in every case and inflection.
3. **System labels must match what the device actually shows.** Several strings tell the user
   to find a toggle in Android Settings. Use the wording from the system-label table supplied
   below, exactly. If a phrase is not in the table, use the standard Android wording for that
   locale rather than a literal translation. A user who cannot find the toggle cannot finish
   onboarding.
4. **Length.** Each item carries a `max_chars` budget. Stay inside it. These are buttons,
   list rows, notification titles and a home-screen widget: long strings clip. If the natural
   translation is too long, shorten the phrasing, do not abbreviate with periods.
5. **Write plain text.** No XML escaping, no backslashes before apostrophes, no `&amp;`.
   Write `&` and `'` directly; the build script escapes them.
6. **Typography.** Keep the middle dot separator `·`, the ellipsis character `…`, and the
   em-dash `—` where the source uses them. Use the target language's own quotation marks.

## Context you are given

Each item includes where the string is used: the `section` heading from the resource file,
the screen file, the enclosing composable, and the line it appears on. Use it to decide
whether a word like "Clear" is a verb on a button or a label on a value, and whether
"Held" is a duration stat or a past participle.

## Domain notes

- "Tag" is the physical NFC sticker or card. Use the word an NFC product would use in the
  target language, not the word for a price tag or a label.
- "Block" (verb) is what Monolith does to an app: it prevents it from opening. Not "ban",
  not "delete".
- "Lock in" / "locked in" means the user has turned Monolith on and committed to it.
- "Bypass" is the timed emergency escape hatch. Keep the same term across every string.
- "Time gained" is deliberate: not "time saved", not "time wasted". It is time the user got
  back. The widget strings still say "time saved" for historical reasons; translate those two
  wordings consistently within each group but keep the distinction.
- "Streak" is the consecutive-days sense, as in a habit tracker.
- The code-breaker is a Mastermind-style puzzle. "Exact" / "Wrong slot" / "Absent" are peg
  feedback: right symbol right place, right symbol wrong place, symbol not in the code.
  The `codebreaker_symbol_*` strings name fill patterns of a shape, read out by TalkBack.

## Consistency

A term translated one way in one string must be translated the same way in every other
string of the same batch and of the batches already produced for this locale. The glossary
below lists the choices already made for this locale; reuse them.
