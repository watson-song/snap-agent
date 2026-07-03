# Two-Tier Skill System (Built-in + Uploadable)

## Problem

Currently there is a single `snap-agent.skills-dir` property. Uploaded skills go to the same directory as built-in ones, and the `classpath:` prefix is broken (stripped and treated as a filesystem path). Uploaded skills are lost on restart because the default `classpath:/skills/` resolves to a non-existent filesystem path.

## Solution

Split skills into two tiers:

1. **Built-in skills** — bundled inside the JAR on the classpath. Read-only, zero-config, cannot be deleted. Survive restarts by definition (they're in the JAR).
2. **Uploadable skills** — stored on the filesystem in a configurable directory. Read-write, persist across restarts, can be uploaded and deleted via API.

When a custom skill has the same `name` as a builtin, the custom one wins (shadows the builtin). Deleting the custom skill restores the builtin.

## Config

Replace `snap-agent.skills-dir` with two properties:

```yaml
snap-agent:
  builtin-skills-dir: classpath:/docs/skills/    # classpath, read-only, packaged in JAR
  upload-skills-dir: /tmp/snap-agent-skills       # filesystem, read-write, persists across restarts
```

No backwards compatibility for the old `skills-dir` property. Early alpha, no existing users to break.

## Skill Directory Structure Rules

Both builtin and upload directories support two skill formats:

```
skills/
  my-skill.md                    # standalone skill -> parsed directly
  dolphin-task-schedule/         # directory skill -> entire dir is one skill
    SKILL.md                     # entry file -> parsed
    REFERENCE.md                 # auxiliary -> ignored by scanner
    some-config.json             # auxiliary -> ignored
  diagnostics/                   # organizational dir (no SKILL.md) -> recurse
    db-check.md                  # standalone skill -> parsed
    performance/                 # subdirectory skill
      SKILL.md
      REFERENCE.md
```

### Scanning logic (applies to both filesystem and classpath)

1. Recursively walk the skills directory
2. When entering a subdirectory, check if it contains `SKILL.md`:
   - **Yes** -> parse `SKILL.md` as a skill, **skip all other files** in that directory (auxiliary), **stop recursing** into it
   - **No** -> recurse (it's an organizational folder)
3. When encountering a standalone `.md` file (not `SKILL.md` inside a directory-skill):
   - Parse it as a standalone skill

The root skills directory is always treated as an organizational directory (never a skill directory itself), even if it contains a `SKILL.md`.

### Filesystem scanner (`SkillRegistry.scan()`)

Uses `Files.walkFileTree` with `preVisitDirectory` override:
- If `dir.equals(skillsDir)` (root) -> always `CONTINUE`
- If subdirectory contains `SKILL.md` -> parse it, return `SKIP_SUBTREE`
- Else -> `CONTINUE` (recurse)

`visitFile`:
- `.md` files not already handled by `preVisitDirectory` -> parse as standalone skill
- Non-`.md` files -> skip

### Classpath scanner (`ClasspathSkillScanner`)

Uses `PathMatchingResourcePatternResolver` to resolve `classpath:/docs/skills/**/*.md`:
1. Resolve all `.md` resources
2. Group by parent directory
3. For each directory group:
   - Contains `SKILL.md` -> parse only `SKILL.md`, skip others
   - No `SKILL.md` -> all `.md` files are standalone skills, parse each

Parsed once at bean creation (classpath doesn't change at runtime).

## Core Module Changes

### `SkillMeta` — new fields

- `source` (String): `"builtin"` or `"custom"`
- `overridesBuiltin` (boolean): `true` when a custom skill shadows a builtin with the same name

### `SkillRegistry` — enhanced

**Constructor:**
```java
public SkillRegistry(Path uploadDir, List<SkillMeta> builtinSkills, ToolDispatcher dispatcher)
```

**Internal state:**
- `List<SkillMeta> builtinMetas` — static list parsed once at construction (from `ClasspathSkillScanner`)
- `Map<String, Path> customSkillPaths` — skill name -> file/directory path (for delete)

**`scan()` flow:**
1. Start with builtin skills: validate tools, set `source="builtin"`
2. Walk upload dir filesystem:
   - Directory with `SKILL.md` -> parse `SKILL.md`, validate tools, set `source="custom"`, record path in `customSkillPaths` (the directory path)
   - Standalone `.md` -> parse, validate tools, set `source="custom"`, record path in `customSkillPaths` (the file path)
   - Skip INVALID entries (no frontmatter, parse failure)
3. Merge: custom overrides builtin by name
   - If a custom skill name matches a builtin name -> set `overridesBuiltin=true` on the custom `SkillMeta`
   - The builtin version is excluded from the merged list
4. Build merged `all` list and `byName` map

**`all()`:** returns merged list (builtin + custom, custom wins on name conflict)

**`get(name)`:** returns custom if exists, else builtin

**`refresh()`:** re-scans upload dir + re-validates tools for both builtin and custom + re-merges. Builtin list is re-parsed from the static `builtinMetas` (not re-read from classpath).

**New methods:**
- `isBuiltin(String name)` — true if a builtin skill with this name exists
- `getCustomSkillPath(String name)` — returns the file or directory path for a custom skill (for delete)

**`RefreshResult`** — unchanged (counts from merged list)

## Starter Module Changes

### `SnapAgentProperties`

- Remove `skillsDir`
- Add `builtinSkillsDir` (String, default `classpath:/docs/skills/`)
- Add `uploadSkillsDir` (String, default `/tmp/snap-agent-skills`)

### New: `ClasspathSkillScanner`

Spring-dependent class in the starter module:

```java
public class ClasspathSkillScanner {
    public List<SkillMeta> scan(String classpathDir) {
        // Uses PathMatchingResourcePatternResolver
        // Resolves classpath:/docs/skills/**/*.md
        // Groups by parent directory
        // Directory with SKILL.md -> parse only SKILL.md
        // Directory without SKILL.md -> all .md files are standalone skills
        // Returns List<SkillMeta> with source="builtin" (no tool validation)
    }
}
```

- `@Bean` in autoconfig, created once at startup
- Uses `SkillLoader` (from core) for parsing
- Does NOT validate tools (that happens in `SkillRegistry`)

### `SnapAgentAutoConfiguration`

- `classpathSkillScanner` bean: creates `ClasspathSkillScanner`
- `skillRegistry` bean:
  ```java
  List<SkillMeta> builtin = classpathSkillScanner.scan(props.getBuiltinSkillsDir());
  Path uploadDir = resolveUploadDir(props.getUploadSkillsDir());
  return new SkillRegistry(uploadDir, builtin, toolDispatcher);
  ```
- Upload dir created on startup if it doesn't exist (`Files.createDirectories(uploadDir)`)

### `SnapAgentController`

**Upload endpoints** (`POST /skills/upload`, `POST /skills/upload-folder`):
- Write to `upload-skills-dir` only — no `classpath:` prefix stripping
- `.zip` extracts to `{uploadDir}/{zipName}/` (directory skill if contains `SKILL.md`)
- `.md` writes to `{uploadDir}/{filename}` (standalone skill)
- `upload-folder` writes to `{uploadDir}/{dirName}/` (directory skill if contains `SKILL.md`)

**New `DELETE /skills/{name}`:**
- Skill not found in merged list -> 404
- Builtin only (not custom) -> 403 `"cannot delete builtin skill"`
- Custom, standalone file -> delete file -> `refresh()` -> 200
- Custom, directory skill -> delete entire directory -> `refresh()` -> 200
- After delete, builtin with same name (if any) re-appears in merged list

**`toSkillDto()`:** add `source` and `overridesBuiltin` fields to JSON response

**`listSkills()`:** returns merged list (unchanged, but now includes source info)

### Built-in skill files

- Location: `snap-agent-spring-boot-2x-starter/src/main/resources/docs/skills/`
- Ship one example: `health-check.md` (standalone, demonstrates the format)
- Users add built-in skills by placing files/directories here at build time

## Error Handling

- Upload dir doesn't exist -> create on startup (`mkdirs`)
- Upload dir is null/empty/unconfigured -> log warning, only builtin skills load; upload/delete endpoints return 400 `"upload-skills-dir not configured"`
- Upload dir not writable -> log warning, skills still load from classpath (builtin only)
- Classpath `docs/skills/` not found -> log info (may be intentionally empty), builtin list is empty
- `SKILL.md` in a directory but parse fails -> log warning, skip that directory skill (don't add INVALID)
- Delete target path is a directory with contents -> delete recursively (it's a skill directory, all contents are auxiliary)
- Duplicate custom skill names (two files/dirs with same `name` frontmatter) -> log warning, last-scanned wins; the overwritten one is not included in the merged list

## Testing

### Core `SkillRegistryTest`

- Update for new constructor (uploadDir, builtinSkills, dispatcher)
- Test merge: custom overrides builtin by name
- Test `overridesBuiltin` flag is set correctly
- Test directory-skill scanning: `SKILL.md` + auxiliary files (only `SKILL.md` parsed)
- Test nested directory skills (organizational dirs recurse, skill dirs don't)
- Test delete: standalone file skill
- Test delete: directory skill (entire directory deleted)
- Test delete: builtin skill returns 403 (via controller test)
- Test delete: custom skill restores overridden builtin
- Test auxiliary `.md` files are skipped
- Test INVALID entries are skipped (existing behavior, now with directory awareness)

### Starter

- `ClasspathSkillScannerTest`: classpath resolution + directory-skill logic + standalone skill logic
- `SnapAgentControllerTest`: `DELETE /skills/{name}` endpoint (404, 403, 200 for file, 200 for directory, restore builtin)
- `SnapAgentPropertiesTest`: new property defaults

## Out of Scope

- Skill hot-reload (WatchService) — roadmap v0.2
- Skill versioning / history
- Skill validation rules beyond frontmatter parsing
- Auxiliary file serving (e.g., letting skills reference REFERENCE.md content at runtime) — future enhancement
