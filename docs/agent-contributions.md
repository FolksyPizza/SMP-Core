# Agent-assisted contributions

Coding agents can help investigate issues, propose changes and run checks. The human contributor remains
responsible for everything submitted. Read [AGENTS.md](../AGENTS.md) and [CONTRIBUTING.md](../CONTRIBUTING.md) first.

## A contribution with human review

1. Define the player-visible outcome and affected modules. Ask the agent to inspect existing implementation,
   permissions, persistence and tests before proposing a change.
2. For substantial work, review the proposed approach and verification plan as a human before implementation.
   Choose a small milestone whose behavior can be demonstrated.
3. During implementation, review a coherent diff at that milestone. Revisit the plan if new persistence,
   permission, dependency or platform implications appear; do not continue expanding scope unchecked.
4. Run the applicable checks in a disposable environment. Read failures and resulting code, rather than relying
   on the agent's summary. Record the version, behavior exercised and remaining gaps.
5. Review every file in the final diff before submitting. Have a human perform the merge review; an agent's review
   can assist, but cannot substitute for it. Deployments are separate from contribution approval.

A small documentation correction need not use a formal design document. Human review of the submitted diff
still applies. Contributors should be able to explain the code and support its behavior after it merges.

## Example request

> Read AGENTS.md and inspect the home command's permission and persistence paths. Propose a focused fix and
> verification plan for the reported failure. Pause for human review of the approach before substantial edits.
> After implementation, show the diff and evidence, including anything that still needs a real client.

## Optional project skill

[skills/smp-core-change/SKILL.md](../skills/smp-core-change/SKILL.md) is an optional project-specific skill.
With a skill-capable agent, make that directory available through its supported local skill installation process.
With other agents, supply its instructions alongside AGENTS.md. No particular agent vendor is required.
The skill does not grant permission to publish, deploy or touch live data.

## Responsibility and privacy

Review generated code for permissions, unsafe item or money mutations, thread access and dependency licensing.
Do not submit code you cannot explain. Keep private prompts, credentials, server logs, player data and internal
operational records out of issues and pull requests. Only the intentionally public project guidance belongs here.
