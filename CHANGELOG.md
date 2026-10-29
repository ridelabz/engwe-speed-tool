# Changelog

What changed in each release. Releases are on the
[Releases](https://github.com/ridelabz/engwe-speed-tool/releases) page, each with its legal and safety text.

## 1.0.1

2026-10-29.

- **Less text on the dashboard:** the speed and assist cards no longer repeat the bike's confirmation in
  words, nor the time it came. The speed's green box with its ✓, and the assist card's bar and heading, show
  it; screen readers hear the confirmation when it comes. On an untested model, the speed card keeps its note
  that a speed may not reset when the bike turns off.

## 1.0.0

The first release, 2026-10-29.

- **Find and check your bike:** finds ENGWE bikes nearby (or the one already paired with the phone), checks
  that it speaks the protocol and reads its model. It never pairs a bike itself. The ENGWE E26 3.0 Pro with
  IoT firmware 218CAN is verified; other models that pass the checks are unverified and say so.
- **Dashboard:** under a top bar with the app's name and its menu, your bike first (its name, the power
  button where the bike offers it, and whether it is on, off or locked, or connecting), then the battery as
  a large gauge, the speed limit, the assist level, the lights and the readings (speed, max speed,
  odometer, temperature, battery health, voltage and total ride time). The cards keep their place while the
  bike isn't connected, their controls greyed out. A card says what it needs in one line when there is
  news; its ⓘ explains the rest. Tapping Verified or Unverified explains verification and offers a report.
- **Speed limit:** 16, 25, 32 or 45 km/h, each shown as set only once the bike confirms it. A warning
  before you set a speed above 25 km/h, stronger at 45 (you can turn it off for a speed); going back to
  25 km/h is always one tap.
- **Keep this speed:** optionally sets your speed again each time the app reconnects to the bike and, on the
  E26 3.0 Pro, after the bike is switched off and on, at most once a minute.
- **Lights:** on and off from the app.
- **Turn off and Turn on:** on the E26 3.0 Pro, the app switches the bike off and on by locking and unlocking
  it, as the ENGWE app's lock button does; off only while the bike reads 0 km/h, and shown as done only once
  the bike answers and its status shows it off or on. In the project's tests, turning it off and on from the
  app reset the limit to 25 km/h, as switching it off and on at the display does.
- **Assist level:** 0 to 5 from the app on the E26 3.0 Pro, shown as set only once the bike confirms the
  command and shows the new level. "Keep this assist level" (not yet tried on the bike) optionally sets it
  again each time the app connects and, on the E26 3.0 Pro, after the bike is switched off and on, at most
  once a minute, only while the bike reads 0 km/h. Other models show their level without the control.
- **Reports:** a compatibility check of your bike, whose result you can report or share as text; session
  logs you can read in full before saving them to Downloads; and reports that show the log they attach,
  then save it and open GitHub's issue form filled in, in your browser, with one tap.
- **Privacy:** the app needs only Bluetooth. No internet access and no backups. Its logs hold no
  identifiers (no Bluetooth address, serial number or names); they record the phone's and the bike's
  models, the bike's readings and the Bluetooth services a compatibility check found, and you read them in
  full before saving one.
- **Unverified bikes:** after you offer to report results, the next app open asks how a speed worked and
  fills in a report with your answers.
- **Settings:** keep the screen on, show the warnings again, stored logs, and, from Android 13, the app's
  language (English for now; ready for translations).
