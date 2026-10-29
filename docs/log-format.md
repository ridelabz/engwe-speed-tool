# Session log format (schema 2)

The app writes one JSON object per line. Every record is built and sanitised in one place when it is
written. There is no field for a Bluetooth address, advertisement data or a time of day, and no free
text: states, actions and error locations are names defined in the app's code.
The one text that comes from the bike is its model identity (`identity`), checked as described there.
Besides it, the bike's own data are its status values and, in `compatibility_check`, the first 8 hex digits of
the custom UUIDs of the services it declares.

Common fields: `schema` (2), `seq` (per session, from 0), `t` (seconds since the session started,
3 decimals), `event`.

| `event` | Fields |
| --- | --- |
| `session_start` | `date` (YYYY-MM-DD, no time), `app_version`, `android` (release), `sdk`, `phone` (manufacturer and model). Values that don't look like plain versions or model names become `unknown` |
| `tx` | `command` (`set_speed_limit_32`, `set_lights_on`, `set_lights_off`, `set_assist_level_3`, `lock`, `unlock`, `read_identity`, …), `length`, `raw_hex` |
| `rx` | `kind` (`frame`, `unframed`, `malformed`, `incomplete`); for frames `command` (a known code or `other`) and `crc_ok`; for malformed bytes `reason` (a fixed code); `length`; `raw_hex` only when the bytes may be logged; `sanitised: true` when bytes were withheld or masked |
| `status` | decoded values (`speed_kmh`, `max_speed_kmh`, `odometer_km`, `assist_level`, `battery_pct`, `lights`, `power`, `temperature_raw`, `battery_health_raw`, `voltage_v`, `ride_time_min` (the bike's total ride time in minutes), as available) and `unverified` (names of values whose meaning isn't verified for this bike). The session records one when the decoded values change, not for every frame (each frame is still an `rx` record) |
| `scan_result` | `name` (always `ENGWE-**` or `**`) and `rssi`. Nothing else of the advertisement is recorded: its manufacturer data contains the Bluetooth address. |
| `identity` | Only for the reply to the app's own identity read, with a valid CRC. `model`: the text before the first NUL: 1 to 31 printable ASCII characters without spaces, no run of 8 or more digits, nothing that looks like a Bluetooth address, a run of 12 or more hex digits, an e-mail address or an advertised-name suffix such as `ENGWE-7C`. Otherwise `usable: false`. Nothing else of the reply |
| `classification` | `verification` (`verified`, `unverified`, `incompatible`) and `reason` for unverified bikes (`unknown_model`, `profile_not_verified`). A bike is verified when its whole model identity matches a verified profile exactly. |
| `state` | `name`, `value` (a name or a number). The session uses `check` (`searching`, `connecting`, `services`, `notifications`, `status`, `identity`), `search` (`paired_direct`: a bike paired with the phone is connected to directly, without searching), `mtu` (a number), `session` (`ready`, `idle`), `incompatible` (`no_service`, `no_notify`, `no_acknowledged_write`, `no_status`, `no_identity`), `stopped` and `retrying` (`not_found`, `connect_failed`, `link_lost`, `bluetooth_unavailable`, `not_accepted`), `attempt` (failed attempts in a row, a number), `link_failure` (`timeout`, `gatt_error`, `disconnected`, `closed`, `bluetooth_off`, `not_permitted`, `unsupported`), `gatt_status` (the Bluetooth stack's numeric status code), `command` (how a speed, light, assist-level or lock command, the `tx` record before it, ended: `confirmed` by the bike's echo within 2 s, plus the status for lights on a verified bike and for every assist level (a later status reporting the bike on and showing the level, within 3 s); a `lock` or `unlock` by the bike's lock-state answer (`0109`, same value) within 2 s and a status with both verified power flags off or on within 6 s; `not_confirmed`; `failed`, the write failed, the link ended or the command was cancelled. A command whose run was stopped or replaced leaves its `tx` without a `command` record), `refused` (a speed, light, assist-level or lock command that wasn't sent, and why: `not_accepted`, `not_ready`, `not_supported` (the bike's profile doesn't offer that assist level, or lock control), `not_acknowledged`, `caveat_not_seen`, `bike_not_on`, `bike_not_off` (an unlock, and the bike didn't report off), `moving` (a lock, and the bike didn't report a verified 0 km/h), `busy`), `reapply` ("keep this speed" attempts to send the kept speed by itself: `reconnect`, once on a new connection; `restart`, once after each power-on the status shows (the verified power flags off, then on: switched off and on at the display, or a lock and an unlock, the app's own Turn off and Turn on included); written once it may send, followed by its `tx` and `command`, or by a `refused` record) and `reapply_skipped` (a re-apply left out: `rider_chose`, the rider sent a speed on this connection or since the power-on; `recent`, one was written less than a minute before; `busy`, another command was being sent), `reapply_assist` ("keep this assist level" sends the kept level by itself: `reconnect`, once on a new connection, and `restart`, once after each power-on, each right after any `reapply` for the same occasion; followed like `reapply`) and `reapply_assist_skipped` (`rider_chose`, the rider set a level from the app on this connection or since the power-on; `recent`; `busy`; `moving`, the bike didn't read 0 km/h; `no_speed`, no recent speed reading whose meaning is verified for this bike; `not_supported`, the bike's profile doesn't offer the kept level). A re-apply also leaves no record when the bike wasn't on from the start of the connection, didn't report on within 30 s of a power-on, or didn't stay on |
| `compatibility_check` | The result of a compatibility check (Diagnostics): one record when a check ends; a check the rider leaves, or one ended by the app going behind another, leaves none. `outcome`: `checked`; `link_lost`, the connection ended during the sample; `incompatible`; `interrupted`, the app connected for something else; `not_connected`, the check didn't connect and finish its checks. `reason` for `incompatible` (the failed check, as in `incompatible` above) and `not_connected` (`not_found`, `connect_failed`, `link_lost`, `bluetooth_unavailable`, `not_accepted`, `too_long`). `checks`: each protocol check (`service`, `notify`, `acknowledged_write`, `status`, `identity`) with `passed`, `failed` or `not_run`. `status_frames`: status frames read; `sampled_s`: seconds of status read (absent when the check never got to reading status). `unreadable_frames`: true when status frames came that didn't fit the bike's layout. `values`: each status value (`speed`, `max_speed`, `odometer`, `assist_level`, `battery`, `lights`, `power`, `voltage`, `ride_time`) with `plausible`, `out_of_range`, `went_back` (a total went down) or `not_read`, never the values themselves; empty when no status was read. `unverified`: the values read whose meaning isn't verified for this bike. `services`: the Bluetooth services the bike declared, each with its `uuid` and `characteristics` (`uuid`; `properties`: `broadcast`, `read`, `write_without_response`, `write`, `notify`, `indicate`, `signed_write`, `extended_properties`; `descriptors`: their UUIDs). A 16-bit Bluetooth number is written as `0x1820`; any other UUID with only its first 8 hex digits (`6e400001-****-****-****-************`): the rest may be chosen per device, and some devices put their address there. An interrupted check lists no services |
| `action` | `name` |
| `error` | `where`, and either `code` or `exception` (the exception's class name only). The session uses `where` = `session`, `link_failure` (the link's event stream failed), `mtu`, `notifications`, `identity`, `command` (a speed, light, assist-level or lock write failed); the app uses `crash` (an uncaught exception) and `log_storage` (the stored log couldn't be written; once per run) |

## What `raw_hex` contains

- Outgoing frames: the full frame (only the app's fixed commands exist).
- Incoming frames with a valid CRC: status frames of the reference length, and one-byte wheel-motion,
  speed, light, assist-level, lock and lock-state frames, in full.
- Identity replies: the model string is kept; everything after it and the CRC are replaced by `2A`
  bytes, so the CRC check fails by design (`sanitised: true`).
- Everything else (other commands, bad CRC, unexpected lengths, bytes outside complete frames): not
  recorded, only `length`.

## Known limits

- Status frames are logged in full. On the reference bike they contain ride data only. Bikes of other
  models might use some status bytes differently.
- Ride data such as the odometer and the total ride time, together with the date, describes a particular
  bike and can link reports of the same bike.
- A compatibility check records the first 8 hex digits of the bike's custom service and characteristic
  UUIDs. Makers use them for the short number of a service; a maker that set them per device would make
  them link reports of the same bike.

Logs are only shared when the user exports them, after a preview.

## Stored logs

The app keeps the session logs of its last 5 runs, at most 2 MB together and 1 MB per run, as files named
`session-<n>.jsonl` (a number only) in its private storage, which is excluded from cloud backup and device
transfer. A run's log is written there now and then (every 30 seconds while the app runs, if it changed, and
when the app goes behind another one); a run whose log grows past 1 MB keeps its first record
(`session_start`) and its last whole records. Older runs go first when the limits are reached. The logs
stay across app updates; Settings → "Delete stored logs" deletes them all (this run's log starts again from
its first record). A gap in `seq` marks records that were dropped: by the size limit, or by deleting. The files' modification times show when they were written, so only their
content is ever shared, never the files themselves. An uncaught exception is recorded as an `error` record
(`where` = `crash`, the exception's class name only) and the log is written before the app ends. Nothing
leaves the phone unless the rider exports a log.

## Export

Diagnostics lists the stored logs (this run's and the earlier ones, with their sizes). Opening one shows a
readable summary, first the bike model(s) as the log carries them (the only text the bike sent: the app can't
always tell whether a bike added its serial number to it, so the rider checks), then the date, app, Android,
phone, the speed, light, assist-level and lock commands with how they ended, refusals, connection retries, errors and length;
then every line, exactly as the saved file will hold it. The summary is shown, not saved. "Save to Downloads"
writes that same text, and only it, as a new file in the phone's Downloads folder (through Android's media
store, which needs no storage permission): `engwe-speed-tool-<date>-<n>.log`, the log's own date and a number
per date, no time of day (Android adds a number if a file of that name is already there). If Downloads can't
take it, Android's file picker opens with the same name. The content is JSON Lines; the `.log` extension is
one that GitHub accepts for attachments. The stored file itself is never shared.

A report about the bike shows the log it attaches in its second step, line by line, with the same summary:
this run's log as it was when the report opened (for a ride test, the log of the run it is about while the
phone still has it; otherwise this run's, and the sheet says so). Its last step, "Save log and open GitHub",
saves exactly that text in Downloads the same way, then opens the form in the browser and says which file to
attach. Demo mode saves and opens nothing.

Files in Downloads can be read by apps with access to the rider's files (all-files access, or the storage
permission in older apps) and copied by backup or sync apps, and they stay after the app is uninstalled: the
report sheet and the preview say so before saving. Delete a log once it is attached.
