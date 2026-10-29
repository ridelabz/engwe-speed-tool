# Compatibility

Which bikes the project has tested, and how a model gets verified. The app reads a bike's **model
identity** (the model text the bike sends when asked) and compares it with the profiles in
[`protocol/src/main/resources/profiles/`](protocol/src/main/resources/profiles/). Only an exact match with a
verified profile counts as verified; nothing else is compared.

## Verified

| Model | Model identity | IoT firmware tested | Speeds tested |
| --- | --- | --- | --- |
| ENGWE E26 3.0 Pro | `ENGWE_Omni_ebike1100EU317` | 218CAN | 16, 25, 32 and 45 km/h |

What the project tested on this bike:

| What | Result |
| --- | --- |
| Setting the limit | The bike confirmed each of 16, 25, 32 and 45 km/h. |
| Riding with the limit set | Assistance stopped at about 16, 25 and 32 km/h. With 45 km/h set, the motor assisted up to about 45 km/h and eased off there. |
| Switching the bike off and on | The limit went back to 25 km/h, tested after setting 16, 32 and 45 km/h. |
| Locking and unlocking the bike | To the app, a lock and an unlock look exactly like switching the bike off and on, and they reset the limit too: with 32 km/h set, turned off and on from this app, assistance stopped at 25 km/h. So after the bike reports switching off and on, the app says the limit is back to 25 km/h, as the project's tests showed. |
| Turning the bike off and on from the app | The app locks and unlocks the bike, as the ENGWE app's lock button does: the display went off and came on again. Turn on also switched on a bike switched off at its display, and the display's own button switched on a bike the app had turned off. It works without the ENGWE app's connection preamble. The app turns the bike off only while it reads 0 km/h (covered by the app's tests, not tried on the bike), and shows a change as done only when the bike answers and its status shows it off or on. Not tried yet: connecting to a bike the app turned off after the app was closed; if this app can't reach it, switch it on at its display or unlock it in the ENGWE app. |
| Keep this speed | After the app reconnected, it set the kept speed again once, and the bike confirmed it. With 32 km/h kept, turned off and on from the app, it set 32 km/h again once the bike was on, the bike confirmed it, and assistance stopped at about 32 km/h. Switching off and on at the display looks the same to the app; not tried separately. |
| Lights | On and off from the app, confirmed by the bike and seen on the bike. |
| Assist level | Each level from 0 to 5 set from the app showed on the bike's display (changes of more than one level at once haven't been tried yet). The app shows a level as set only when the bike confirms the command and its status shows the new level. "Keep this assist level" (set again once when the app connects and once after each power-on it sees, only while the bike reads 0 km/h) hasn't been tried on the bike yet. |
| Readings | The speed followed a hand-spun wheel; max speed, odometer, assist level, battery, lights and whether the bike is on matched the bike's display; temperature, battery health, voltage and total ride time matched the ENGWE app. |

The project has tested only this bike and this firmware. The app can't read the firmware version, so it
shows any bike with this model identity as verified: check its IoT firmware version in the ENGWE app. On
other firmware versions, a speed change might behave differently, including staying after the bike is
switched off. Tap 25 when you're done, and please report what you see.

## Unverified

Any other bike that passes the app's protocol checks is **unverified**: speed, light and assist changes may not
work, or may work differently, and a speed change might not reset when the bike turns off. The app shows
its readings as unverified and asks you to acknowledge this before the first change (see
[SAFETY.md](SAFETY.md)).

Models with reports are listed here until they are verified, each with its model identity (after checking
that it holds no serial number), the IoT firmware versions reported, what worked and what didn't, and links
to the reports. Reports for a verified model on other firmware are listed here too, by firmware version.

| Model identity | IoT firmware reported | What worked and what didn't | Reports |
| --- | --- | --- | --- |
| *None yet* | | | |

## Reporting how it worked

In the app, open the menu and choose **Report**, then **How it worked on my bike**. The app fills in the
GitHub form with what it knows and what you tell it (for example the model identity, the app version, the
Android version and phone model, the speeds and assist levels the bike confirmed and where assistance
stopped), and shows you all of it first, with every line of the session log it attaches. **Save log and open
GitHub** saves the log in your phone's Downloads folder and opens the form in your browser; you attach the
log there. **Diagnostics → Compatibility check** reads what the bike offers for 30
seconds without changing anything; its result screen can report the result the same way (**Report this
result**), or share it as text with an app you choose.

You can also open a new issue on GitHub and choose **How it worked on my bike**. Attach a session log
(Diagnostics → tap the log → Save to Downloads) after checking its preview.

Ride only where it is legal, for example on private land. Give the speed at which assistance stopped as a
number; don't share GPS tracks, ride maps, serial numbers or photos of the bike's frame number.

## How a model gets verified

1. Compatibility reports for the same model identity accumulate in the issues, and the model is listed
   under **Unverified** above.
2. A model is verified with **at least 2 independent reports** (different riders, different bikes), each
   with an attached session log that shows the bike's confirmation, and each saying that with the limit set
   to 32 km/h, assistance stopped within **±2 km/h** of it. The dashboard's values are confirmed against
   the bike's display or the ENGWE app.
3. A pull request adds or updates the profile (model identity, value map, the IoT firmware versions tested,
   links to the reports); a maintainer reviews it and the checks pass.
4. The next release ships the profile, and this file is updated.
