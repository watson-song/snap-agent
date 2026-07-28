## Summary

<!-- Brief description of what changed and why -->

## Changes

- [ ] Code changes
- [ ] Test changes (TDD: red → green → refactor)
- [ ] Documentation updates (see checklist below)
- [ ] Config/dependency changes

## Documentation Checklist

If this PR includes code changes (`.java` files), verify documentation is updated:

- [ ] **API changes?** → Updated `docs/embeed-skills-agent/06-api-and-ui.md` or REST API docs
- [ ] **Architecture changes?** → Updated `docs/embeed-skills-agent/01-architecture.md`
- [ ] **New tool/component?** → Updated `docs/site/architecture/` and `docs/INTEGRATION.md`
- [ ] **Config/properties change?** → Updated `docs/embeed-skills-agent/04-configuration.md`
- [ ] **Behavior change visible to host app?** → Updated `docs/INTEGRATION.md`
- [ ] **No doc changes needed** → Explain why:

> Pre-commit hook warns when `fix:`/`feat:`/`refactor:` commits have code changes but no `.md` changes.
> This PR template is the second line of defense — reviewers should verify docs are in sync.

## Test Plan

- [ ] Unit tests pass
- [ ] Integration tests pass (if applicable)
- [ ] Manual verification (describe below):
