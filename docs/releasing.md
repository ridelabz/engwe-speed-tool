# Releasing

Releases are built by [`.github/workflows/release.yml`](../.github/workflows/release.yml) when a version
tag is pushed on `main`, in two jobs:

- **build** runs Gradle (and with it every plugin and dependency) without any secret, checks the source
  and bytecode boundaries, and hands the unsigned APK to the next job.
- **release** runs only the Android SDK tools and this repository's scripts, in the `release`
  environment that holds the key: it checks the APK, signs it, checks the signature and drafts a GitHub
  Release. A maintainer approves the job before it starts, reads the draft (its "What's new" comes from
  `CHANGELOG.md`) and publishes it.

## Once: the signing key

The release key signs every release for as long as the app exists: a lost key means users can't update,
a leaked one lets anyone ship an "update". Create it on a computer you trust, with a strong password,
and keep an offline backup (for example on two USB drives in different places).

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore engwe-speed-tool-release.p12 \
  -alias release -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=ENGWE Speed Tool"
```

`keytool` asks for the password; in a PKCS12 keystore the key's password is the same. The certificate's
subject is only `CN=ENGWE Speed Tool`: no names, places or organisations (the workflow refuses any
other). Its SHA-256 fingerprint, in lower case without colons:

```bash
keytool -list -v -keystore engwe-speed-tool-release.p12 -alias release \
  | sed -n 's/.*SHA256: //p' | tr -d : | tr A-F a-f
```

Publish it in the README ("Install"), so users can check what they install.

## Once: the repository settings

1. **Settings → Environments → New environment** `release`:
   - **Required reviewers**: the maintainers, and untick **Allow administrators to bypass configured
     protection rules**;
   - **Deployment branches and tags**: selected tags, `v*`.

   Outside GitHub Enterprise, required reviewers work only in public repositories (on GitHub Free,
   environment secrets too), so releases start once the repository is public. Don't push a version tag before the
   environment is set up: a tag pushed earlier creates it unprotected (the run then fails, as it has no
   secrets, but no one approved it).
2. **Environment secrets** of `release` (not repository secrets: those reach every workflow without an
   approval):
   - `RELEASE_KEYSTORE_BASE64`: the keystore as base64 (`base64 -w0 engwe-speed-tool-release.p12` on Linux,
     `base64 -i engwe-speed-tool-release.p12` on macOS)
   - `RELEASE_KEYSTORE_PASSWORD` and `RELEASE_KEY_PASSWORD`: the password (the same for both)
   - `RELEASE_KEY_ALIAS`: `release`
   - `APK_PRIVATE_TEXT`: text that must never be in an APK, one item per line (the maintainers' names,
     user names, e-mail addresses, the addresses and serials of their bikes and phones); a find is
     reported without the text. The release job refuses to run without it.
3. **Environment variable** `RELEASE_CERT_SHA256`: the fingerprint above. The job refuses an APK signed
   with any other certificate, and always one signed with the public test key.
4. **Settings → Rules → Rulesets → New tag ruleset** for `refs/tags/v*`: restrict creations, updates and
   deletions to the maintainers. The workflow that runs is the copy in the tagged commit, so only a tag
   pushed from `main` by a maintainer may start it.

## Each release

1. In a pull request, set `versionName` (`MAJOR.MINOR.PATCH`) in `app/build.gradle.kts` (the version code
   follows from it), add the version's section to `CHANGELOG.md` (it becomes the release's "What's new"), and
   update the README and `COMPATIBILITY.md` if needed. Merge it.
2. Tag the merged commit on `main` and push the tag:

   ```bash
   git tag -a v1.2.3 -m "ENGWE Speed Tool 1.2.3"
   git push origin v1.2.3
   ```

3. **build** checks that the tag is on `main` and names `versionName`, and builds without the build cache,
   with dependency verification.
4. Approve **release** in Actions, **only for a tag you pushed from `main`**. It checks the APK:
   - the manifest (permissions, no backup, not debuggable), the package name, version and label;
   - no build-machine paths and none of the private text;
   - the bike profiles are inside;

   then signs it and checks the signature: one signer, the release certificate (`RELEASE_CERT_SHA256`),
   subject `CN=ENGWE Speed Tool`, never the public test key.
5. Read the draft release: the APK, `SHA256SUMS`, and notes with the legal and safety text from
   `SAFETY.md` word for word (the firmware caveat included), and "What's new" from the version's section of
   `CHANGELOG.md` (a placeholder there means the section is missing: fix it before publishing). Then
   publish.

Obtainium (README, "Updates") offers its users each published release that isn't a pre-release. So keep
exactly one APK per release, never publish a test or candidate build as a normal release (mark it as a
pre-release), and don't change the assets or the tag of a published release.

A failed run creates no release. Fix the cause in a pull request, then delete the tag (`git push origin
:v1.2.3` and `git tag -d v1.2.3`; with the ruleset, a maintainer allowed to delete tags does this) and
tag again.
