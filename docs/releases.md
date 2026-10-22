# Releases, upgrades and licensing

The [changelog](../CHANGELOG.md) is the versioned source of release notes. Download published artifacts from
[GitHub Releases](https://github.com/FolksyPizza/SMP-Core/releases). An Unreleased entry describes source changes
that may not be present in an existing release jar. Verify the tag, commit and artifact before deploying.

## Current documentation baseline

This proposal describes the public 1.0.0 source line, with the subsequent source licensing checks recorded under
Unreleased. Network functionality is beta and duels are preview. It does not announce private economy candidates,
cross-region home updates or other unshipped work as released features. See [compatibility](compatibility.md).

## Apache 2.0 scope

Current first-party source, documentation and build scripts use the Apache License, Version 2.0.
Read [LICENSE](../LICENSE) and [NOTICE](../NOTICE). Older MIT releases retain the MIT License; do not relabel them.
Third-party components retain their original licenses. PizzaProxyGuard packages MariaDB Connector/J's LGPL notice.
The source/package license checker verifies the expected notices; it does not relicense dependencies.

A source licensing update does not silently replace an older GitHub release asset. Operators should inspect the
license metadata in the actual jar they deploy. Release maintainers should publish newly verified artifacts with
clear version/revision identification rather than implying historical artifacts changed.

## Preparing a release

1. Select a clean revision, keep plugin descriptor versions aligned, and move completed Unreleased entries into
   the intended version/date section. Describe observable changes and known limits.
2. Run the [build and verification procedure](development.md), including package licensing. Exercise affected
   gameplay and network paths; document gaps and server/client versions.
3. Include upgrade/config/schema steps, dependency changes, downtime requirements and the recovery procedure.
4. Record source commit and jar hashes with the artifacts. Verify LICENSE/NOTICE and third-party notices in each jar.
5. Have a human review the diff, artifacts and evidence before publishing the tag, release notes and jars.

See [operations](operations.md) for installation, maintenance and rollback. No particular upgrade is promised
zero downtime or rollback compatibility unless that exact path has been tested.
