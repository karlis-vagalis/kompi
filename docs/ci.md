# CI checks and artifacts

`CI ready` reports the result of every job selected for a change, including both daemon bundle runners. A selected job that fails, is cancelled or is skipped makes the gate fail.

- Documentation-only changes skip builds. Hook-config-only changes run `prek validate-config` (or `validate-manifest`); they don't run the hooks themselves.
- On PRs, Android app changes select the Android build and emulator regression. Daemon source changes select host tests, notices and both daemon bundles. Combined changes select the union.
- Code changes on `main` still produce both daemon bundles, notices, the emulator APKs and the unsigned release APKs. Shared code, build inputs and unrecognised paths run the full suite. So does an event whose Git diff can't be established.
- The tag release calls CI with `package: true`. It runs the full suite without restoring or saving caches and produces the redistribution source archive. Ordinary CI prepares only the notices used by the daemon bundles.

PRs can restore dependency downloads, but don't restore build outputs or publish caches. Only `main` caches Gradle task outputs and verified Android native staging. The native cache requires an exact match for the toolchain and tracked native inputs; a missing or damaged cache causes a rebuild. Cargo caches downloads, not `target/`, and the Gradle task cache is capped at 350 MiB per weekly entry. Neither cache stores signing material.

Run the routing and gate tests locally with `python3 -B -m unittest discover -s scripts -p 'test_ci_*.py'`. Update `scripts/ci-plan.py` and its tests together when adding a job or changing its inputs.
