# Cyclone V5 Alpha 41 — Hands: Cyclone can type into any app

Developer alpha for owner testing, built on Alpha 40 (Planes), which it includes. Mobile is `5.0.0-alpha.41.dev1`
(version code 183), the Windows companion is `1.6.0-alpha.41`, and the bundled Glass web app is `1.0.0-alpha.24`.
This is Phase 0 and Phase 1 of plan 21 (Mind Hands and Desk).

## What changed

**Cyclone Mind can type again, everywhere.** Building this release found a second wall behind the one plan 21
described. When the Mind typed, the phone's safety layer only accepted text in Chrome's address bar. Everywhere else
(ChatGPT, Keep, Gmail, WhatsApp) the text was refused, whatever the model did. A Mind mission is your own request, so
it may now type into ordinary text fields. Passwords, codes, card numbers and fields that look like them are still
refused; those go through the Secrets Card. Typing never sends: sending, posting, paying and deleting still ask you.

**The phone owns delivery, and proves it.** Cyclone puts the text in, then reads the field back:
- If the field does not hold the text, Cyclone pastes it instead and reads it again. The pasted clip is marked private
  (no preview), and your own clipboard is put back afterwards.
- Long drafts (over 4 000 characters) are pasted straight away; up to 20 000 characters.
- When the field never shows the text (some apps hide it from accessibility), the Mind is told it could not be
  checked. It is no longer reported as typed.

**Text boxes keep their identity.** A composer is usually a text box inside a clickable container. Its label is its
hint, and the hint changes when it gets focus or text. Cyclone's safety check refused both situations, which is why
the ChatGPT run could not even tap the reply box. Both now count as the same box. A different control that overlaps
it, or two separate fields with the same name, are still refused.

**Refusals say why.** Instead of "the element moved or disappeared" for every refusal, the Mind hears the real reason
and the next thing to try, for example: "could not tell that control apart from others (a safety check, nothing was
pressed): tap_point on it, then type_text with focused=true".

**Tap the box, then type.** `type_text` with `focused: true` types into the text box that has focus, as a person does
after tapping it. A tap that opens the keyboard now counts as progress ("the text box e5 has focus"), so the Mind
does not tap it again.

**No more loops.** If the same box refuses twice in the same way, the Mind is told to try something different. After
four failed attempts in a row, Cyclone copies the draft and asks you: *"I wrote this but could not enter it. It's
copied: tap the text box and paste."* with **Done** / **Try again**. The Mind writes to the clipboard but never reads it.

**Runs you can read.** Mind missions now show one step per action in Glass's run inspector, with tool calls and
failures counted and a cause: *Text not entered* or *Action failed*, pointing at the step.

**A Lab suite for hands.** Glass → Lab → suite *hands* runs text-delivery missions:
- ChatGPT prompt, Keep note (short and ~2 000 characters), Gmail draft body;
- WhatsApp *Message yourself* draft and Google Messages draft;
- Chrome, Settings and Play Store search.

Each checks the typed text on the screen, and the drafts check that nothing was sent. Apps that are not installed are
skipped.

## Validation and limits

Tests that pass:
- **Android unit tests:** owner-mission typing and its refusals; a synthetic ChatGPT-composer fixture for the safety
  check (container, changed hint, raw mirror, same-id fields, an overlapping sibling); the delivery ladder (set-text
  proven, set-text no-op then paste, neither, long drafts); focused typing; refusal texts; the loop breaker and
  handoff; the Mind run record.
- **Other suites:** gateway (the Hands suite), MCP, Glass and companion tests, and the CI guards.

**Physical Pixel 8 acceptance is UNVERIFIED.** The plan's bar is ChatGPT ≥ 19/20 and the Hands suite ≥ 95% on the
phone.

Honest limits:
- The ChatGPT fixture is synthetic, shaped like the failed run. The real tree still has to be captured with
  `debug.snapshot`; it will become the regression fixture.
- Paste restores your previous clip only when Android lets Cyclone read it; otherwise Cyclone clears its own clip.
- Background-screen typing (Planes) keeps its existing rule (empty fields only).
- The Desk (a scratchpad for drafts) is plan 21 Phase 2, after Cyclone Drive.

Install the APK as an update; do not uninstall. It is signed with the rotated Cyclone release key and its signing
lineage (the alpha.34 / alpha.39 / alpha.40 signer).

Suggested checks:
1. Ask: "Open ChatGPT and ask it for three better examples of a good cover letter". The prompt should appear in the
   composer and be sent.
2. Ask: "Make a Keep note with a 300-word story about a lighthouse". It should be one note, written in one go.
3. Glass → Lab → suite *hands* → run 1 repetition. Every installed app should pass.
