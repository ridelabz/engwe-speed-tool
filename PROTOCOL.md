# Bluetooth protocol, as observed

This describes only what the project has observed on its own reference bike: an **ENGWE E26 3.0 Pro
with IoT firmware 218CAN**, from its Bluetooth traffic and from ride tests. Other models and other
firmware versions are unverified. They may behave differently, including keeping a speed setting
permanently.

## Transport

- The bike advertises a name starting with `ENGWE`, slowly; scan for at least 15 seconds.
- The advertisement's manufacturer-specific data starts with the bike's own Bluetooth address. The app
  reads nothing from it: it uses only the advertised name, the device address (to connect) and the
  signal strength.
- A bike paired with a phone usually doesn't advertise: while Bluetooth is on, Android keeps a link to
  a paired bike (using the ENGWE app can leave a bike paired). The app connects to a paired bike
  directly, without searching. It never pairs or unpairs a bike itself.
- GATT service `00001820-0000-1000-8000-00805f9b34fb` with:
  - `00001822-…` **write** (Write Request and Write Without Response). The app uses Write Request.
  - `00001821-…` **notify**, with a client configuration descriptor (`2902`).
- Always resolve characteristics by UUID; handle numbers differ between platforms.
- The bike asks for an MTU of 512. Before the MTU exchange, a notification can arrive cut to 20 bytes.
- The bike sends status frames without being asked, even before notifications are enabled: about once
  a second with the display on, and about once every 5 seconds with the display off or the bike locked.

## Frame format

All multi-byte fields are big-endian.

```
45 6E | command (2) | length (2) | payload (length) | CRC (2) | 77 65
```

The CRC is CRC-16/MODBUS (initial value `FFFF`, reflected polynomial `A001`) over command, length and
payload, written **high byte first**. One notification can carry several frames, and one frame can be
split across notifications.

## Commands this app sends

| Purpose | Frame | Observed |
| --- | --- | --- |
| Assistance limit 16 km/h | `456E2A6500011056237765` | echoed; assistance stopped at 16 km/h when ridden |
| Assistance limit 25 km/h | `456E2A6500011950E37765` | echoed; 25 km/h is the bike's own setting after it is switched on |
| Assistance limit 32 km/h | `456E2A6500012042237765` | echoed; assistance stopped at 32 km/h when ridden; back to 25 km/h after the bike was switched off and on |
| Assistance limit 45 km/h | `456E2A6500012D87E27765` | echoed; assistance continued to about 45 km/h and eased there when ridden; back to 25 km/h after the bike was switched off and on |
| Lights on | `456E05970001FFA4857765` | echoed; status shows lights on; lights came on |
| Lights off | `456E0597000100E4C57765` | echoed; status shows lights off |
| Assist level 0 | `456E1009000100CFE77765` | set from the app, the display showed level 0; also seen in captured traffic of another app, echoed, the status showing assist 0 about a second later |
| Assist level 1 | `456E10090001010F267765` | set from the app, the display showed level 1; also seen in captured traffic of another app, as for 0 |
| Assist level 2 | `456E10090001020E667765` | set from the app, the display showed level 2 |
| Assist level 3 | `456E1009000103CEA77765` | set from the app, the display showed level 3 |
| Assist level 4 | `456E10090001040CE67765` | set from the app, the display showed level 4 |
| Assist level 5 | `456E1009000105CC277765` | set from the app, the display showed level 5 |
| Lock (turn off) | `456E0108000102F19B7765` | sent from this app: the display went off; in captured traffic of another app, answered with the lock state `456E01090001020D9A7765` and both power flags off at once. With 32 km/h set before, after a lock and an unlock assistance stopped at 25 km/h |
| Unlock (turn on) | `456E0108000101F0DB7765` | sent from this app: the display came on, also after the bike was switched off at its display; in captured traffic of another app, answered with the lock state `456E01090001010CDA7765` and both power flags on at once |
| Read identity | `456E24110002FF010CDB7765` | answered with an identity frame (below) |

The speed value is one unsigned byte in km/h. The assist value is one unsigned byte, the absolute level (0
to 5, the reference bike's display levels): each level set from the app showed on the display. Changes of
more than one level at once haven't been tried on the bike yet. The app offers the assist control only on
bikes whose profile gives their number of levels and maps the assist level reading. The lock value is one
byte, `02` locked or `01` unlocked; the app uses a lock to turn the bike off and an unlock to turn it on,
only on bikes whose profile offers it (`lock_control`) with verified power flags, a lock only while the bike
reports on and a verified 0 km/h, an unlock only while it reports off. Nothing else can be sent: the app's
command set is closed in code.

**Confirmation.** When the bike acknowledges a speed, light or assist command it repeats the same frame
(command and payload). Echoes arrived within 0.03–1.4 s. An echo shows that the bike acknowledged the
frame; only riding shows the actual cutoff. The app counts an assist level as confirmed only when, besides
the echo, a later status shows the new level (byte 18; in captured traffic it changed about a second after
the echo). A lock or an unlock isn't echoed: the bike answers with its lock state (`0109`, same value)
within about 0.1 s and sends a status with both power flags off or on at once; the app counts it as
confirmed only with that answer within 2 s and such a status within 6 s (with the bike off a status comes
only about every 5 s). The identity read is answered with a reply, not an echo.

**Not persistent.** On the reference bike the limit returns to 25 km/h when the display is switched off
and on again (observed after setting 16, 32 and 45 km/h), and also after a lock and an unlock (set 32 km/h,
turned off and on from this app, ridden: assistance stopped at 25 km/h). To the app the two look the same
(the power flags go off, then on), so it treats every power-on it sees as a restart. An unlock also switches
on a bike switched off at its display, and the display's power button switches on a locked bike. The ENGWE
app sent its connection preamble (`0703 01`, `2B46 01`) about a second before its lock; this app sends none,
and its lock and unlock work without it, as the light, speed and assist commands do. Not tried yet:
connecting to a locked bike and checking it, which turning it on after the app was closed needs, and the
app's refusal to lock a moving bike, which only its tests cover.

**No readback found.** No way to read the current limit has been found: a read request for it returned
`00` with the display off and with the default 25 km/h active. After a restart the app says only what the
project's tests showed on the reference bike (the limit back at 25 km/h), never that it read the limit.

**Read requests** use command `2411` with the 2-byte command to read as payload; the reply carried that
command for the two reads observed (identity and the limit). The app only reads the identity.

## Status frame (`2A63`, 54-byte payload)

Offsets are into the payload. Unsigned, big-endian.

| Offset | Size | Value | Scale | On the reference bike |
| --- | --- | --- | --- | --- |
| 0 | 2 | speed | 0.1 km/h | verified (wheel spin) |
| 2 | 2 | max speed since power-on | 0.1 km/h | verified against the display |
| 16 | 2 | odometer | 0.1 km | verified against the display |
| 18 | 1 | assist level | 1 | verified (changes on the display and from the app); resets to 0 when the bike is switched on |
| 21 | 1 | battery | 1 % | verified against the display |
| 28 | 1 | lights: `00` off, `FF` on | — | verified |
| 36 | 1 | temperature | 1 °C | verified against the ENGWE app |
| 37 | 1 | battery health | 1 % | verified against the ENGWE app |
| 39, 40 | 1 each | power/display flags: both `00` when the display is off | — | verified; at power-on byte 40 has returned up to about 2 s before byte 39. A lock and an unlock in the ENGWE app give the same values and resets as the display switching off and on |
| 42 | 2 | voltage | 0.01 V | verified against the ENGWE app |
| 48 | 2 | total ride time | 1 minute | verified against the ENGWE app |

No status byte was found that reflects the assistance limit (checked at the default 25 km/h).

## Other frames from the bike

- `2A64` with `01` when the wheel starts turning and `02` when it stops.

## Identity frame (`FF01`)

The reply to the identity read is a 54-byte payload: the ASCII model string (on the reference bike
`ENGWE_Omni_ebike1100EU317`), NUL padding, a short field, more NUL padding, the bike's **serial number**
and a final NUL.

The app keeps only the model string. The serial is discarded on arrival and masked in logs.

## Verified bikes

A bike counts as verified only when its whole model string matches a verified profile exactly:
`ENGWE_Omni_ebike1100EU317`. Nothing else is compared. The app can't read a bike's firmware version over
Bluetooth (the ENGWE app shows it as the IoT version). Any other model string is unverified, with every
status value marked unverified.

## Not covered

- Any command other than those above. Their effects on this or other bikes are not known to this
  project, and the app cannot send them.
- Other models, other firmware versions, and whether the limit persists on them.
- Whether other firmware versions report the same model string: so far one bike with one firmware has
  been seen.
