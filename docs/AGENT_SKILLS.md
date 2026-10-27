# Portable Agent Skills in AURA

Source: https://agentskills.io/specification

Skills are folders with `SKILL.md` YAML frontmatter containing `name` and `description`. Optional scripts, assets and references are not executed by AURA simply because files exist.

## Locations
- Built-in skills: `engine/skills/<skill>/SKILL.md` tracked in the canonical repo.
- User skills: `~/.aura/engine/skills/<skill>/SKILL.md` (not committed; import requires user approval).
- Discovery reads metadata only; activation loads the full file with max length. Skills act as **instructions**, not bypasses for operating-system privileges.

## Permissions
Any skill execution through a CLI must create an ordinary `skills/run` job requiring human approval and bounded workspace access. Treat imported skills as untrusted. Never automatically launch bundled scripts or allow a skill to approve its own jobs.

## Built-ins
- `vibe-coding`: repo first, contract, design, TDD, verification.
- `android-diagnosis`: ADB read-only evidence, no silent writes.
- `file-organization`: inventory, reversible proposals, no first-pass deletion.

## Economics
Progressive disclosure: inventory reads name and description; complete instructions load only for a selected skill, with scoped workspace/task context. Cache summaries only when their source and revision are unchanged. Prefer scripts for deterministic repeatable actions over model calls.
