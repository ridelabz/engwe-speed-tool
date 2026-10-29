## What changes

<!-- One thing per pull request. What does it change, and why? Link the issue or report if there is one. -->

## How it was tested

<!-- Tests added or changed. If you tried it on a bike: the model, the IoT firmware version, and what you saw.
Share only the frames that matter, as hex, with addresses and serials removed; never a raw capture or bug report.
Never paste a Bluetooth address, serial number, advertised-name suffix, name or e-mail address. -->

## Safety checklist

- [ ] No new command to the bike, and no way to send other bytes (or: the captured evidence and a maintainer's decision are linked).
- [ ] The offered speeds are unchanged, and going back to 25 km/h stays one tap away.
- [ ] Nothing that identifies a bike, a phone or a person (Bluetooth address, serial number, advertised-name suffix, name, e-mail address, time of day) in logs, reports, tests, fixtures, screenshots or commit messages.
- [ ] No code, strings or decompiled material from other apps.
- [ ] User-visible text is in string resources; tests cover the change; CI passes.
- [ ] If this touches protected paths (`.github/protected-paths.txt`), I understand the test build is linked only after a maintainer's review.
