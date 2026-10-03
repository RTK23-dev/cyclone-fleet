# Cyclone V5 Alpha 102: Numbers

Developer alpha for owner testing. It builds on Alpha 101 and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.102.dev1` (version code 247).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.102.dev1.exe` (runtime `5.0.0-alpha.102.dev1`).
- **Glass:** `1.0.0-alpha.57`.

## What's new

**Glass → Command Center → Numbers:** one place for every phone number Cyclone can receive codes on, across the fleet.

**At a glance:**
- how many numbers you have, and how many are ready for codes;
- which numbers need you;
- rentals that end in the next 7 days;
- numbers not used by an account yet.

**Where each number's texts arrive:**
- **Phones in the fleet:** each phone reports its SIM's number, or the number you confirmed on it (Settings →
  Permissions → Codes). For example "Pixel 8 · SIM 1".
- **Forwarded by a plugin:** a Cyclone Ports plugin that forwards texts for the number, such as an SMS forwarder on
  another phone.
- **Rented:** a number you rent for a longer term. It shows the provider, the end date, and the plugin that forwards
  its texts.
- **Just tracked:** kept here for reference; codes don't arrive automatically.

**For every number, Glass says whether it's ready, and if not, why:**
- the phone is offline;
- codes from texts are off on the phone;
- it has no forwarding plugin, or the plugin is paused;
- the rental ended;
- it's paused.

**Phones to set up:** phones whose numbers can't be read yet are listed, each with the one step to fix it. For example
"On the phone: Settings → Permissions → Codes → allow Read texts".

**Managing numbers:**
- **Label** a number (a niche, a client).
- **Pause** a number; agents then don't use it.
- **Assign** a number to one account. One number, one account: an account that already has a number can't take a
  second.
- **Add** a rented, forwarded or tracked number; edit its provider, end date and plugin; remove it, with a confirm step.
- **A phone's own numbers** come from the phone. Pause them here; remove them on the phone.
- **Check phones now** asks every phone again. Otherwise phones are asked at most once a minute.

**For agents:** `GET /v1/accounts/{id}/number` tells an agent which number an account uses. That's read only: nothing
in Cyclone rents, buys or releases a number.

## Privacy

- **Numbers only:** no text, sender or code is stored on the PC, sent by the phone for this page, or shown in Glass.
- **Checked twice:** the phone's report is checked on the PC. A report with anything besides numbers is refused.

## Not in this build

- **Automatic rental codes:** codes from a rented number arrive automatically only when a plugin forwards its texts.
  A provider integration comes when its API docs are in.
- **Account Setup rows** don't pick a number from this page yet.
- **Codes from Google Messages** (for apps whose texts Android holds back) are still to come.

## Tests

- **New:**
  - `test_numbers.py` (10): phone numbers and their origin, reading each phone at most once a minute, offline and
    older phones, adding rented, forwarded and tracked numbers, one number per account, pausing and removing, a rented
    number found on a phone, the PC's check of the phone's report, and the routes (login token, errors).
  - `NumbersReportTest` (3): numbers only, one row per number, at most 8.
  - `numbers.test.mjs` (6): reading the gateway's answer safely, the overview, filters, assign, pause and remove
    (with confirm), adding a rented number, the empty page and retry.
  - Guard `test_numbers_guard.py`: no place for a text or code, nothing rents or buys a number, every route needs the
    login token, numbers only from the phone, the op on phone, bridge and PC.
- **Results:**
  - full phone suite: 2360 tests, 0 failures, and lint clean;
  - gateway: 813 passed;
  - Glass: 294 passed, guard clean;
  - Ports kit: 21 passed;
  - CI guards: all 286 pass.

## Physical acceptance

UNVERIFIED. With the PC runtime running and phones paired:
1. On each phone, turn on Settings → Permissions → Codes and allow Read texts.
2. Glass → Command Center → Numbers: each phone's number appears with its phone's name and "Ready".
3. Add a rented number with a provider and an end date. Assign it to an account. The account can't take a second
   number.
4. Pause a number, turn Codes off on one phone and disconnect another. Each shows the right state and reason.
