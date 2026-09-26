# CLAUDE.md — handlive-android

Android app of HandLive (Kotlin, Gradle KTS, minSdk 29 / targetSdk 35). It runs Handoff and the other continuity features on Android: the phone stays in sync with a Mac, iPhone and iPad, and those Apple devices sync back. One part of the HandLive **workspace**: the hub repository `handlive` is this directory's parent, holds the specification that all code implements, and its `CLAUDE.md` applies here in full.

## Workspace layout (mandatory)

```
<workspace>/        hub repo "handlive": CLAUDE.md (read first), docs/, plans/, tools/docs/, tools/workspace.sh
  android/          this repo (handlive-android)
  shared/           repo "handlive-shared": test-vectors/, schemas/, design-tokens/, tools/
```

Gradle reads `../shared/design-tokens/tokens.json` (task `:core:design:generateHandLiveTheme`); unit tests read `../shared/test-vectors` and `../shared/schemas` through the system property `hl.shared.dir`, and `core/design` tests read `../docs/design-system/1-foundations` (`hl.docs.dir`) from the hub. Outside this layout nothing builds. From the hub, `tools/workspace.sh clone <group-url>` checks the parts out and `tools/workspace.sh status` shows all five repositories.

## Working here

- Read in this order: `../CLAUDE.md` → `../docs/detailed-design/README.md` → `../docs/detailed-design/00-common-specs.md` → the phase file in `../plans/20260925-implementation/` → the leaf specs it names → `../docs/code-standards.md`. UI work also reads `../docs/design-system/README.md`, `../docs/design-system/3-platforms/03-android.md` and the component READMEs.
- Contracts (wire format, error codes, settings keys, UI strings) come from the hub docs; change them there first, never in code. Test vectors, schemas and tokens change only in `../shared` (repo handlive-shared) as their own commits — say so in the report so the Apple and relay agents re-run their tests.
- Build and test: `./gradlew check` (JDK 21; `local.properties` → `sdk.dir` with `platforms;android-37.0`). Instrumented tests (`src/androidTest`, `./gradlew connectedDebugAndroidTest`) run on a real device when the task card says so.
- Branches: `feat/phase-0N-<slug>` per phase. Commit early and small — one commit per logical step (scaffold, module, tests, docs), conventional commits (`feat(android): …`, `test(android): …`), no AI references. A commit never spans repositories. Commit before writing the report and list the hashes with the repository name in `../plans/20260925-implementation/reports/`.
- CI (`.github/workflows/ci-android.yml`) reproduces the layout: the hub at the workspace root, this repo into `android/`, handlive-shared into `shared/`. It does not run when only shared changes — start it by hand (workflow_dispatch).
