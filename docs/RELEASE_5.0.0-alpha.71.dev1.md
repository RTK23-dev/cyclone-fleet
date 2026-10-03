# Cyclone V5 Alpha 71: Drive, polished

Developer alpha for owner testing. It builds on Alpha 70 (the AI screen's official scene) and includes it.
- **Mobile:** `5.0.0-alpha.71.dev1` (version code 216).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.71.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

Three changes to Driver mode, all from the owner.

## What changed

**1. A short film when you turn Driver mode on.**
- About nine seconds on a night road, from Settings, the Ask panel or the Quick Settings tile ("Cyclone Drive"):
  - "Tap the orb and talk": the Ask bubble grows into the Drive orb, a tap lands and the orb listens.
  - "It answers in one line": it thinks, says one line, then "Sent".
  - "Anything risky waits": a payment turns the orb warm and waits while the car slows to a stop.
  - "Drive safe": Stop is always one tap. Got it.
- **Controls:** tap anywhere for the next scene; Skip or Back closes it. It works upright and sideways, TalkBack
  reads every scene, and with Android's animations off each scene holds still.
- **It uses the real Drive button**, so what you see is what you get.
- **The microphone is asked for at the end** ("Allow the microphone" / "Not now") when Drive does not have it yet.
  The switches no longer open the permission prompt themselves.

**2. Hold the Drive button one second, then drag it anywhere.**
- It stays exactly where you let go. It used to snap to a side.
- It is kept wholly on screen, per orientation.
- A spot saved by an older build (a side) becomes that edge.

**3. "Listening" is said once.**
- AI mode showed a small "LISTENING" above the large "Listening…". Now only the large one shows.
- The rule is general: the small status word is hidden whenever a caption already says it. Other states keep it
  ("Thinking" over "One moment…").

## Tests

- `DriveIntroScriptTest` (8 tests):
  - the three scenes and the close, and their captions;
  - the bubble growing into the orb, the tap and listening;
  - one line, then "Sent";
  - the warm wait while the car stops;
  - the road never going backwards;
  - taps moving to the next scene;
  - the captions' fades;
  - every scene held still with animations off.
- `VoiceFaceTest`:
  - a dropped button stays where it was let go, wholly on screen, including after a rotation and on a tiny screen;
  - listening is said once (and "Thinking" keeps its word).
- `AskScreenR3ContractTest`: the Ask switch starts the film, and the film asks for the microphone.
- Mobile CI: unit tests, lint and the release build pass. Repository guards pass, and versions are coherent.

## Limits

- **Physical: UNVERIFIED.**
  - The film was reviewed from a browser preview of the same design, with the same timings, texts and colours. It
    has not been seen on a phone yet.
  - Dragging the button anywhere has not been tried on the Pixel yet.
- **The film plays each time Driver mode is turned on.** Skip ends it at once.
