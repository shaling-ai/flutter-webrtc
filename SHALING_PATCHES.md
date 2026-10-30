# Shaling AI fork of flutter_webrtc: maintainer guide

This repository is Shaling AI's fork of [flutter-webrtc/flutter-webrtc](https://github.com/flutter-webrtc/flutter-webrtc),
the Flutter package `flutter_webrtc`. It exists so that an app can keep an exact upstream release while moving its
native WebRTC binary to an upstream bug-fix build. It is not an official flutter-webrtc project and is not published
to pub.dev: apps use it as a git dependency pinned to a commit SHA.

## Rules

1. **Everything here is public**: code, comments, commit messages, branch and tag names, pull requests and reviews.
   Never write internal information into any of them: no user, account, session or device identifiers, no crash
   report links or counts, no internal links, hosts or repository names, no credentials.
2. **Commit as your GitHub noreply address** (`<id>+<login>@users.noreply.github.com`).
3. **Pull requests go to this repository**, against the line you change (`shaling/v1.5.2`). With the CLI, run
   `gh repo set-default shaling-ai/flutter-webrtc` once per clone. GitHub preselects the upstream repository as the
   base of a fork's pull request; switch it.
4. **`shaling/*` branches and tags are append-only.** Never force-push, rebase in place or delete them. Apps pin
   commits by SHA, and pub fetches a git dependency by cloning this repository's refs. Repository rulesets enforce
   this.
5. **Leave the default branch and upstream's tags alone.** The default branch is upstream's as it was when we
   forked; nobody maintains it. GitHub Actions is off, so the inherited workflows (pub.dev publishing and others)
   never run here. Do not use "Sync fork" on a `shaling/*` branch, and push named refs only, never `--all`, `--tags`
   or `--mirror`.
6. **Keep the diff to what the app needs.** A patch here is a dependency pin or a small fix that upstream already
   shipped in a later release. Anything else belongs upstream.
7. **Keep upstream's version.** `version:` in `pubspec.yaml` stays at the upstream value. The commit SHA identifies
   the fork.
8. **Mark every change.** Each modified upstream file carries a `Modified by Shaling AI in <year>` notice near its
   top, and every changed site has a comment that starts with `Shaling AI fork patch`, so
   `git grep -n "Shaling AI fork patch"` lists them all. `LICENSE` and `NOTICE` stay unchanged.

## Branches and tags

| Ref | What it is |
|---|---|
| `shaling/v1.5.2` | Maintenance line for upstream 1.5.2: upstream tag `v1.5.2` (`073aaca`) plus our commits, linear and append-only. |
| tags `shaling/v1.5.2-<n>` | One annotated tag for each commit an app pins, numbered from 1. Never moved or deleted. |

`v1.5.2` matches the package published on pub.dev as `flutter_webrtc 1.5.2` (the published archive omits dotfiles,
`example/`, `.github/` and `test/`).

## Pinned commits

Apps pin only commits listed here. The tip of a line may be ahead of its last tag with documentation commits.

| Tag | Commit | Base | Patches |
|---|---|---|---|

## Patches

### W1: WebRTC-SDK 144.7559.10 on iOS and macOS

| | |
|---|---|
| Files | `ios/flutter_webrtc.podspec`, `macos/flutter_webrtc.podspec` |
| Android, Windows, Linux | Unchanged (`io.github.webrtc-sdk:android:144.7559.09`, libwebrtc m144.7559.09). |
| Behaviour switch | None. This is a crash fix inside the native binary; nothing that works on `.09` changes. |
| Upstream | Same bump as flutter-webrtc/flutter-webrtc#2158, shipped in `flutter_webrtc 1.6.0+hotfix.1`. |
| Drop when | The app moves to an upstream `flutter_webrtc` release whose Darwin podspec pins `144.7559.10` or later. |

**Problem.** WebRTC-SDK `144.7559.09` (pinned by `flutter_webrtc 1.5.0`–`1.6.0`) can crash with `EXC_BAD_ACCESS` on a
WebRTC worker thread. `AudioEngineDevice` creates `fine_audio_buffer_` and uses it under different guards, so a
stop/start sequence can reach `FineAudioBuffer::ResetPlayout()` with a null buffer. The faulting instruction is
`str xzr, [x0, #0x28]` (`playout_buffer_.Clear()`). It is typically reached while `-[RTCPeerConnection close]` runs on
the main thread with the app in the background. The symbol names in crash reports are nearest-export noise (for
example `webrtc::FrequencyTracker::FrequencyTracker`, `webrtc::Port::SharedSocket`, `webrtc::CreateIceTransport`). See
flutter-webrtc/flutter-webrtc#2148 and #2153.

**Fix.** webrtc-sdk/webrtc@f47af7bc9658 ("Fix AudioEngineDevice crash from null FineAudioBuffer on playout/record
start", webrtc-sdk/webrtc#256) adds `EnsureFineAudioBuffer()` at four call sites. It shipped in WebRTC-SDK
`144.7559.10` on 2026-06-17.

**Why the pin alone is safe.** The Dart, Objective-C and Java sources stay at upstream 1.5.2. The two binaries were
compared directly (the `.10` archive's sha256 is
`64e9150f5ad467a11f5adfdf41c251d74278060368777727a981a3320df1d5ff`, as in upstream #2158's `Package.swift`):

- `WebRTC.framework/Headers` and `Modules/module.modulemap` are byte-identical between `.09` and `.10` for the
  `ios-arm64` and `ios-arm64_x86_64-simulator` slices;
- the exported symbol set of the `ios-arm64` binary is identical (3032 symbols in each);
- `.10` contains the fix's log line `fine_audio_buffer_ was null at buffer start; recreating`, and `.09` does not.

**Pairing.** Every pod in an app that depends on `WebRTC-SDK` must pin the same version, or CocoaPods cannot resolve
the graph. `livekit_client` pins `WebRTC-SDK` in its own podspec, so an app that uses LiveKit also needs a
`livekit_client` build that pins `144.7559.10`.

**Validation.** No unit test can reach this path. Validate on a physical iOS device: start a call that publishes the
microphone, background the app, and end the call while it is in the background. Repeat several times.

## Consumers

```yaml
dependency_overrides:
  flutter_webrtc:
    git:
      url: https://github.com/shaling-ai/flutter-webrtc.git
      ref: <full 40-character SHA>   # shaling/v1.5.2-<n>
```

After `flutter pub get` and `pod install`, check that `ios/Podfile.lock` resolves `WebRTC-SDK (144.7559.10)`.

## Repository settings

| Setting | Value | Why |
|---|---|---|
| Default branch | upstream's, not maintained | "Sync fork" on a maintenance line would merge upstream into it. |
| GitHub Actions | Off | The inherited workflows publish to pub.dev. None of them is ours to run. |
| Ruleset "Protect shaling branches" | `refs/heads/shaling/**`: no deletion, no force-push | Rule 4. |
| Ruleset "Protect shaling tags" | `refs/tags/shaling/**`: no deletion, no update, no force-push | Rule 4. |

## License

MIT, like upstream. `LICENSE` and `NOTICE` are upstream's files, unchanged.
