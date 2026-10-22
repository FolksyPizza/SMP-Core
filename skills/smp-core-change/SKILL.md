---
name: smp-core-change
description: Implement and verify SMP-Core Paper or Velocity plugin contributions, including menus, commands, storage and economy changes. Use within an SMP-Core source checkout.
---

# SMP-Core changes

Locate the repository root and read its AGENTS.md, CONTRIBUTING.md and the affected plugin's descriptor.
Trace the command or event through permissions, scheduler access, storage and cleanup before editing.
Use the root Gradle build; do not assume each plugin's older standalone build is authoritative.

For substantial work, present a bounded approach and verification plan for human review before implementing it.
Return for human review if scope or risk changes, and present the final diff and test evidence for human merge review.
The contributor owns correctness and consequences; agent output does not replace human judgment.

Use PizzaCommon's existing storage, MenuLock and scheduler helpers where the module already depends on them.
Check GUI extraction and deposit paths, not only click handlers. For item or balance mutations, account for a failed
write, duplicate action, disconnect and restart. Verify proxy routing separately from local command behavior.
Preserve plugin descriptor versions, permission defaults and third-party license notices.

Build with `./gradlew build`, then run `python3 tools/ci/check-licenses.py --jars build/dist`.
Select additional tests from the affected behavior and CI. Run server checks only in a dedicated disposable directory;
`tools/ci/boot-check.sh` deletes its work directory. A successful startup does not prove gameplay or networking.
Report the exact verification level and untested paths. Never infer real-client coverage from synthetic checks.
Do not publish artifacts, deploy plugins or mutate live data without separate authorization.
