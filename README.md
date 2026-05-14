# SGUProfiler

Server-side Fabric mod that profiles per-entity tick cost (AI, entity tick, movement, collisions, and optional Carpet fake-player action breakdown). Results are printed in-game via [Carpet Mod](https://github.com/gnembon/fabric-carpet)’s `Messenger` styling.

**[中文说明](README_zh.md)**

## Requirements

| | Version |
|--|--|
| Minecraft | **1.21.1**, **1.21.4**, **1.21.6**–**1.21.11** (including **1.21.10**); per-version Yarn / Fabric API / Carpet: **`stonecutter.properties.toml`** |
| Java | 21+ |
| [Fabric Loader](https://fabricmc.net/) | ≥ 0.16.0 (see `deps.fabric_loader` in TOML) |
| [Fabric API](https://modrinth.com/mod/fabric-api) | matching MC version |

This repo uses [Stonecutter](https://stonecutter.kikugie.dev/wiki/start/) and **loom-back-compat** ([Fabric template](https://github.com/stonecutter-versioning/stonecutter-template-fabric)).

**Optional:** [Carpet](https://github.com/gnembon/fabric-carpet) — enables `profile … bot` and `Attack` / `Use` / other fake-player action categories. Install versions compatible with your Minecraft build.

## Single-player worlds

1. **Runtime:** Java 21, **Fabric Loader** for your MC version, and matching **[Fabric API](https://modrinth.com/mod/fabric-api)**.  
2. **Mods folder:** use **`sguprofiler-<mod_version>+mcYOUR_MC.jar`** matching the game (build one variant with **`:1.21.6:build`** etc., or all with **`buildAndCollect`** → **`build/libs/<mod_version>/`**). Add **[Carpet](https://modrinth.com/mod/carpet)** if you need `profile … bot` / fake-player splits.  
3. **Launch** the game with a **Fabric** profile (not vanilla).  
4. **Permissions:** you must be a vanilla **operator** (`/op YOUR_NAME` or listed in `ops.json`), or listed in **`config/sguprofiler_command_whitelist.json`** by an OP. “Cheats on” without OP and without whitelist entry is **not** enough (the old permission-≥2 bypass was removed).  
5. **In-game:** e.g. `/SGUProfiler profile start` and `… profile stop` (root literal is case-sensitive: use **`SGUProfiler`** exactly). Config: `config/sguprofiler.json`; command whitelist: `config/sguprofiler_command_whitelist.json`.

If something fails, verify the **`+mc…`** jar matches the game, Fabric API is present, and check `logs/latest.log` for SGUProfiler.

## Build (Stonecutter)

Mod id and version live in **`stonecutter.properties.toml`** (`mod.*`). Yarn, Fabric Loader, Fabric API, and Carpet per Minecraft version are under each **`["x.y.z"]`** block.

- **One version** (example 1.21.6):

```bash
./gradlew :1.21.6:build
```

- **All supported versions** (remapped jars collected under **`build/libs/<mod_version>/`**, e.g. `build/libs/0.3.2/`):

```bash
./gradlew buildAndCollect
```

Use **`stonecutter active "…"`** in **`stonecutter.gradle.kts`** and Gradle **“Set active project to …”** tasks for IDE focus ([getting started](https://stonecutter.kikugie.dev/wiki/start/)). Small Minecraft API differences are handled in **`McCompat`** via signature-based reflection so we do not rely on `//?` blocks (which conflict when the active Stonecutter version equals that subproject).

**`gradle.properties`** keeps JVM settings only; do not pin `minecraft_version` there (Stonecutter owns versions).

## Configuration

File: `config/sguprofiler.json` (Fabric server config directory).

| Topic | Details |
|--------|---------|
| First run | A template file is created if missing. |
| `permissionFallbackLevel` | Minimum permission level for non-player sources (e.g. console) to run profiling commands. Default in template: `4`. |
| `sampleEveryNTicks` | Stride for tick sampling (≥ 1). |
| `tickSampleMode` | `STRIDE_ONLY`, `HEAVY_ONLY`, `STRIDE_OR_HEAVY`, `STRIDE_AND_HEAVY` (heavy modes need a positive `heavyLastTickMsThreshold`). |
| `heavyLastTickMsThreshold` | Previous tick wall time (ms) threshold for heavy sampling. |
| `minProfileNanoseconds` | Drop slices shorter than this (0 = keep all). |
| `autoMsptThreshold` | If set to a value greater than zero, start a profile automatically when smoothed MSPT exceeds it. |
| `autoProfileDurationTicks` | Length of auto MSPT session (ticks). |
| `scheduledProfileIntervalMinutes` / `scheduledProfileDurationTicks` | Periodic auto profile (0 = off). |
| `autoCooldownTicks` | Cooldown after an auto MSPT session before another can start. |

## Command whitelist

Non-operator players can be allowed to use **`/SGUProfiler profile …`** if their UUID is listed in:

`config/sguprofiler_command_whitelist.json` — JSON array of UUID strings, e.g. `["xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"]`.

Managing the list is **operators only** (or console with permission level **≥ 4**):

| Command | Description |
|---------|-------------|
| `/SGUProfiler whitelist add <player>` | Add online player’s UUID. |
| `/SGUProfiler whitelist remove <player>` | Remove by UUID (target must be online). |
| `/SGUProfiler whitelist list` | List entries (names from user cache when available). |
| `/SGUProfiler whitelist clear` | Clear the list. |

`<player>` uses vanilla player entity arguments (e.g. `Steve`, `@p`).

## Profiling commands

Prefix: **`/SGUProfiler profile`** (only this root literal is registered).

| Command | Description |
|---------|-------------|
| `start` | Start full profiling (all configured categories). |
| `start overworld` / `nether` / `end` / `all` | Limit to dimension(s). |
| `start bot` | Carpet fake-player actions only (`Attack`, `Use`, other pack time). |
| `start bot <dim…>` | Same as `start bot` with dimension filter. |
| `stop` | Stop profiling; print report. Values are **profiled ms per sampled tick** per entity bucket—**not** the server’s whole-tick MSPT from F3/debug; do not sum rows against MSPT. |

Operators always have access; other players only if their UUID is in **`sguprofiler_command_whitelist.json`**.

## Single-player and “invalid player data”

This mod is **server-side** (`environment: server`); in single-player the integrated server loads it. Commands are registered with Fabric’s `CommandRegistrationCallback` instead of hooking `CommandManager`’s constructor, which avoids fragile registration timing on integrated servers.

If the message persists, **back up the world** and check for `level.dat` / `playerdata` mismatches (e.g. moving a multiplayer world incorrectly), large version jumps, or other mods; try with this mod removed to see if it still happens.

## License

MIT — see [LICENSE](LICENSE).
