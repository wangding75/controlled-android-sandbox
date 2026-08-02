# Commit identity policy

## Canonical identity

Repository commits must use:

```text
OpenAI <openai@users.noreply.github.com>
```

Configure this repository locally with:

```bash
git config user.name OpenAI
git config user.email openai@users.noreply.github.com
```

Do not modify global Git configuration as part of an automated task.

## Historical aliases

The repository contains historical commits created with local, example, or invalid email domains. Their Git object IDs are immutable release evidence and are not rewritten. `.mailmap` maps those aliases to the canonical identity for audit, blame, shortlog, and governance checks.

The supply-chain gate validates every reachable author and committer after mailmap normalization. New commits that use an unapproved canonical identity fail the gate.

## Updating approved identities

An identity update requires all of the following in one reviewed change:

1. Update `verification/approved-commit-identities.txt`.
2. Add only necessary historical aliases to `.mailmap`.
3. Run `python3 scripts/check-m5-t19-1-u-supply-chain-governance.py`.
4. Record the reason in the stage report.

History rewriting, force-push, amend, rebase, and squash are not authorized by this policy.
