# Contributing

Thank you for helping. This is a community project: **it is community-supported, and there is no
individual support** (see [SUPPORT.md](SUPPORT.md)). Questions go to Discussions; bugs, ideas and
compatibility reports go to the issue forms (New issue).

Before anything else, read [SAFETY.md](SAFETY.md). The app changes how a motor assists its rider, so some
rules below are strict on purpose.

## Safety rules for every change

- **No new commands to the bike** without captured evidence that the official app or the bike uses them
  that way, and a maintainer's decision. The app sends a closed set of frames: the identity read, the
  speed limits it offers, and the lights. Never add a way to send arbitrary bytes.
- **No sweeping, brute-forcing or guessing** of commands, values or byte orders on a real bike. No firmware
  flashing, no factory resets, no current-limit or other motor settings.
- **The offered speeds** stay as they are unless reports and a maintainer's decision say otherwise.
- **Going back to 25 km/h** must always stay one tap away.
- **Privacy:** nothing that identifies a bike, a phone or a person goes into logs, reports, tests,
  fixtures, screenshots or commit messages: no Bluetooth addresses, serial numbers, advertised-name
  suffixes, names, e-mail addresses or times of day. Use made-up values in tests.
- **Clean room:** no code, strings or decompiled material from other apps, including ENGWE's. Facts you
  observed yourself (captures, rides) are welcome; say how you observed them.
- **Sharing evidence:** share only the frames that matter, as hex, with every Bluetooth address and serial
  number removed. Never attach a raw capture (an HCI snoop log, an nRF Connect log), an Android bug report,
  or a screenshot that shows an address, a serial number or a device name: they hold the addresses of the
  bike, the phone and other devices nearby.
- **Riding tests:** ride only where it is legal, for example on private land, and report the speed at
  which assistance stopped as a number; no GPS tracks or ride maps.

Changes to protected paths (the bike communication, the sanitiser, the legal and safety text and the
screens that show it, build logic, scripts and workflows; see
[`.github/protected-paths.txt`](.github/protected-paths.txt)) need a maintainer's safety review: only then
is their test build linked.

## Building

The protocol, session and reporting modules are plain Kotlin and need only JDK 21:

```bash
./gradlew -PjvmOnly check
```

The Android app needs the Android SDK. CI builds it, runs its tests and screenshots, and builds a test APK
for every pull request, so you don't need Android Studio to contribute to the JVM modules. With the SDK:

```bash
./gradlew ktlintAndroidCheck :app:check :ble-android:check :app:assembleDebug
```

Other checks CI runs, which you can run too:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
python3 scripts/check_source_boundaries.py
python3 scripts/check_ui_strings.py
```

Code style is ktlint's: `./gradlew -PjvmOnly ktlintFormat` for the JVM modules, and
`./gradlew ktlintAndroidFormat` for the Android ones (with the SDK). User-visible text goes in string
resources.
Every change comes with tests; code that talks to a bike is tested against the simulated bike
(`FakeBike`), never a real one.

Gradle checks every downloaded artifact against `gradle/verification-metadata.xml`. After adding or
upgrading a dependency, record the new checksums with both builds, then review the diff and remove
entries that are no longer used (Gradle only adds):

```bash
./gradlew -PjvmOnly --write-verification-metadata sha256 help check
./gradlew --write-verification-metadata sha256 help ktlintAndroidCheck :app:check :app:assembleRelease
```

## Test builds of pull requests

Every pull request gets a test APK once its checks pass: a bot comments with the download link, its
SHA-256 and a link to the compatibility form for that build. Test builds install next to the release
app, carry a permanent "TEST BUILD · PR #n · not reviewed" banner and are signed with a public test key:
**they are not reviewed**, so install one only if you trust the change. For changes to protected paths, the
link appears only after a maintainer's review.

## Adding a bike

A profile in [`protocol/src/main/resources/profiles/`](protocol/src/main/resources/profiles/) describes one
model: its exact model identity, its display name and where each value sits in its status message. List
the file in `index.txt`. A new profile is **unverified** (`"verified": false`, every value unverified): the
app shows its readings as unverified and, before the first change, asks riders to acknowledge that changes
may not work, or may work differently, and might not reset when the bike turns off. Profiles stay open to
contributors, so a new one gets its test build at once; a maintainer's review gates the test build of a
profile that is marked verified, has any verified value, maps the bike's on/off flags, gives the number of
assist levels or lock control, isn't strict JSON, or removes a profile. Only a verified profile names the IoT
firmware its evidence was gathered with (`tested_firmware`); About's compatibility table shows it.
`assist_levels` (1 to 5) offers the assist control for the model: the app then sets levels 0 up to that
number with its one fixed assist command, and only on a profile that also maps the assist level reading,
which confirms each change. Without it the app shows the level and doesn't set it. `lock_control` (`true`)
offers Turn off and Turn on: the app locks and unlocks the bike with its one fixed lock command, and only on
a profile whose on/off flags are verified, which confirm each change.

To get a model verified, see [COMPATIBILITY.md](COMPATIBILITY.md): at least two independent reports
(different riders and bikes) with session logs showing the bike's confirmation, assistance stopping within
±2 km/h of the limit with it set to 32 km/h, and the dashboard's values checked against the bike's
display.

## Pull requests

Keep them small and about one thing, fill in the template's checklist, and say how you tested. CI must
pass before a merge. By contributing you agree that your contribution is licensed under the
[GPL-3.0](LICENSE), and you follow the [code of conduct](CODE_OF_CONDUCT.md).
