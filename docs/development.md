# Build and test from source

Use a clean SMP-Core checkout, Java 21+, Git, Python 3, and a network connection for dependency resolution.
The Gradle wrapper supplies Gradle 9.6.1 and verifies its distribution checksum. Linux CI uses Temurin 21.

## Build a known revision

Choose a release tag or commit, record `git rev-parse HEAD`, and run from the repository root:

```sh
java -version
python3 tools/ci/check-licenses.py
./gradlew clean build --no-daemon
python3 tools/ci/check-licenses.py --jars build/dist
```

`build/dist/` contains ten plugin jars. PizzaCommon is embedded in its consumers; do not look for a separate
Common plugin. Preserve the Git revision with your artifacts. On Linux, record their checksums with:

```sh
sha256sum build/dist/*.jar
```

These steps make the build process repeatable, not proven byte-for-byte reproducible. The Paper API is a
SNAPSHOT dependency, and other dependency resolution and toolchain inputs must also be captured for identical
future builds. The wrapper checksum pins Gradle, not every input or resulting artifact.

## Isolated startup check

On a development host, choose an unused port and a dedicated disposable work directory:

```sh
BOOT_CHECK_PORT=25590 tools/ci/boot-check.sh build/dist build/boot-check-review
```

The script deletes that work directory before starting. It downloads a pinned Paper 1.21.11 build and
checksum-verified dependencies, binds to loopback, runs in offline mode with local storage, checks expected
plugin enables and error output, then stops the test server. Inspect `build/boot-check-review/server.out`.
Do not expose this offline-mode test to the internet or use a real server directory.
`PAPER_JAR` selects a local Paper jar for checking another supported version.

## What each check establishes

| Check | Evidence | Remaining work |
| --- | --- | --- |
| Gradle build | Source compiles and artifacts package | Gameplay, database failures and client behavior |
| License checker | Current source and jar license metadata | Legal review of new third-party material |
| Boot check | Backend plugins start in a disposable Paper instance | Proxy, shared SQL, real clients and gameplay |
| Focused runtime scenario | The exercised behavior in that environment | Other failure paths and versions |

The published CI runs the source check, Gradle build, package check and Paper boot check. A successful
`./gradlew build` does not establish a comprehensive gameplay unit-test suite. The network smoke script sends
RCON commands to configured running services; inspect it and supply a disposable network before running it.

## Test a behavior change

State the expected player-visible result and test success plus relevant rejection, cancellation, disconnect
and restart paths. For inventory menus, try normal clicks, shift-click, drag and close. For money or items,
check both balance and item conservation, failures and duplicate actions. For network changes, use two
backends and shared SQL; a local test cannot prove cross-server behavior. Check ordinary-player permissions
as well as staff access. Test Java and Bedrock UI paths separately when affected.

In a PR, give exact commands, server/client versions, data mode and results. Distinguish compiled, unit tested,
booted in isolation, integration tested, real-client and production evidence. State untested paths explicitly.
