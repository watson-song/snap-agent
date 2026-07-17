---
name: host-only-skill
description: A skill from the host project that lives in the same classpath:/docs/skills/ path as SnapAgent's built-in skills. If you can see this skill but NOT the built-in ones, the classpath: shadowing bug is reproduced.
tools: []
inputs: []
---

# Host-Only Skill

This skill exists to reproduce the classpath shadowing bug. It should coexist with SnapAgent's 13 built-in skills, not replace them.
