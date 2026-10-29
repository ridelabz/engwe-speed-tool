# Test builds for pull requests

Every pull request gets an installable test APK, so people can try a change on their own bike before
it is merged.

## How it works

1. **Test build** (`.github/workflows/test-build.yml`) builds the APK in a run of its own. It runs no
   tests, no lint and no build cache, so nothing from the pull request runs there except the compilers.
   The APK has its own application ID (`….test`), is signed with the public test key, and shows
   "TEST BUILD · PR #n · not reviewed" on every screen.
2. **CI** (`.github/workflows/ci.yml`) runs the tests and checks.
3. **Test build comment** (`.github/workflows/test-build-comment.yml`) keeps one comment on the pull
   request. It runs `.github/scripts/test_build_comment.py` from `main` and talks only to the GitHub
   API; it never checks out or runs the pull request's code. It posts the download link when, for the
   pull request's current head commit:
   - the latest runs of both workflows for that commit, branch and repository succeeded,
   - the pull request targets `main`,
   - the build was GitHub's merge of this commit into a commit that `main` contains,
   - the APK verifies: it is the only artifact of its name in the Test build run, and its SHA-256 is
     recomputed from the download and matches the build's checksum file and metadata,
   - and the safety gate below is open.

Otherwise the comment says why there is no link: checks still running or failed, not based on `main`,
waiting for a maintainer's safety review, or a build that couldn't be verified. Runs cancelled by a
newer push are ignored; if the latest run is cancelled by hand, the comment keeps saying the checks are
running until the workflow is re-run.

## Safety gate

Changes to protected paths need a maintainer's review before a test build is linked. The list is
`.github/protected-paths.txt`, read from `main`, and `CODEOWNERS` mirrors it. It covers the bike
communication modules, build logic and signing, CI, manifests and backup rules, the test-build
banner, and legal text with the screens where riders accept and read it.

The changes are compared both with `main` and with the commit the build was merged into (an older
`main` if `main` has moved on since), including the old names of renamed files, so a stacked pull
request inherits its parent's protected changes. Files that run during the build (workflows, Gradle
files, scripts, signing) must also be identical to `main`'s; if `main` changed them since the branch
was made, updating the branch from `main` clears that.

Some files stay open to contributors but are checked by content:

- **Bike profiles** (`protocol/src/main/resources/profiles/*.json`): a profile marked verified, with
  verified fields, mapping the power flags, giving the number of assist levels (which offers the assist
  control) or lock control (which may offer turning the bike off and on) needs review, as does one that
  isn't strict JSON. A removed profile needs review too.
- **The profile index**: entries must be plain file names.
- **Value resources** (`res/values*/`): a file that names the app (`app_name`), its launcher icon
  (`ic_launcher…`), the test-build banner or the legal text (`legal_…`), or declares a document type,
  needs review. These resources live in their own protected files, so ordinary string changes stay open.

Symbolic links, submodules, more than 300 changed files, and anything the bot can't check also need
review.

Code outside `ble-android` may not use Bluetooth or reach it indirectly:

- `scripts/check_source_boundaries.py` reads the source (in CI and in the Test build): no Bluetooth,
  class objects, reflection, class loading, native code, raw Binder calls, suppressed compiler errors,
  aliased platform imports or window content outside `MainActivity`; no `.bytes` outside the protocol
  module (meant for an outgoing frame's bytes, which its sanitiser logs; text can't tell types apart);
  no Bluetooth link types (`BikeLink`, `BikeTransport`) outside the session module, so that app code
  reaches a bike only through a session; and no `AndroidBluetooth` outside the app's protected `bike`
  package, which creates every session with the rider's stored acceptance. Kotlin goes in `src/<set>/kotlin`, in the directory of its
  package. In Gradle files, test fixtures may only be test dependencies: they are never checked.
- `scripts/check_bytecode.py` reads the classes compiled for the test APK with `javap -v`: their constant
  pools (including the method handles behind invokedynamic), member declarations and code. No
  `android.bluetooth`, reflection, class loading, native methods, processes or raw Binder calls; no
  `OutgoingFrame.getBytes` outside the protocol module; outside the session module, no `BikeLink` member
  and no `connect` taking a `BikeAddress`, on any class; and outside the session module and the log
  writer's, no call with an `OutgoingFrame` parameter except into the protocol module (frames passed as a
  generic type aren't seen). Exemptions go by the module a class was built in, never by package.
  The last three are defence in depth: what keeps other bytes off the radio is that only `ble-android`
  has Bluetooth and its links accept only an `OutgoingFrame`, which exists only for the app's commands.
  Both checks are tripwires that raise the bar; review remains the last line.
- Android modules compile with warnings as errors, which also rejects any suppression of compiler
  errors.

The Test build also checks the APK: package, label in every language, signer, and that the app name,
launcher colour and banner exist exactly once, in the default configuration.

## Approving a test build (maintainers)

1. Wait until the bot's comment says "Waiting for maintainer safety review" and names a commit.
2. Review the pull request at exactly that commit.
3. Add the `safe-to-test` label. The bot accepts it only from users with admin or maintain permission,
   only while its comment asks for a review of the pull request's current commit, and it checks the
   label's author again on every later update. Otherwise it removes the label and says why.
4. Check that the comment now offers the build for the commit you reviewed.

The approval covers that commit only. A new push removes the label, and the gate closes again.
Approving allows a test build. It is not a review of the whole pull request.

## Repository settings this relies on

- Keep "Send write tokens to workflows from pull requests" (fork pull requests) **off**, so a pull
  request's own workflows can't comment or label as the bot. Fork pull requests in a private repository
  also need "Run workflows from fork pull requests" for test builds.
- Keep no long-lived branches besides `main`: a pull request into another branch runs that branch's
  copy of the comment workflow (it never gets a link, but its workflow file is trusted).
- Squash-merge contributors' pull requests, so `main` never contains a contributor's intermediate
  commits.
