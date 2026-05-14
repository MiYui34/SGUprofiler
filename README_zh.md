# SGUProfiler

基于 Fabric 的**服务端**模组，按实体统计刻上耗时（AI、实体 Tick、位移/传送、碰撞等；安装 Carpet 时还可拆分假人 **Attack** / **Use** / 其余 ActionPack 时间）。结果通过 [Carpet](https://github.com/gnembon/fabric-carpet) 的 `Messenger` 在**游戏内聊天**中着色输出。

**[English](README.md)**

## 环境要求

| | 版本 |
|--|--|
| Minecraft | **1.21.1**、**1.21.4**、**1.21.6**–**1.21.11**（含 **1.21.10**）；各版本 Yarn / Fabric API / Carpet 见 **`stonecutter.properties.toml`** |
| Java | 21+ |
| [Fabric Loader](https://fabricmc.net/) | ≥ 0.16.0（与 `stonecutter.properties.toml` 中 `deps.fabric_loader` 一致） |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 与游戏版本匹配 |

多版本工程基于 [Stonecutter](https://stonecutter.kikugie.dev/wiki/start/) 与 [loom-back-compat](https://github.com/kikugie/loom-back-compat)；官方 Fabric 模板见 [stonecutter-template-fabric](https://github.com/stonecutter-versioning/stonecutter-template-fabric)。

**可选：** [Carpet](https://github.com/gnembon/fabric-carpet) — 使用 `profile … bot` 及假人攻击/交互等分项时需安装，版本需与当前 MC 兼容。

## 在单人存档中使用

1. **安装环境**  
   - 使用 **Java 21**。  
   - 安装 **Fabric Loader**（与游戏版本一致）。  
   - 从 Modrinth 等安装与当前 MC **同版本**的 **[Fabric API](https://modrinth.com/mod/fabric-api)**。

2. **放入模组**  
   - 使用与游戏版本一致的 **`sguprofiler-<mod_version>+mc<你的MC>.jar`**（例如一次打出全部版本见下方 **`buildAndCollect`**，产物在 **`build/libs/<mod_version>/`**）。  
   - 若要用 **`profile … bot`** 或假人分项：再装与 MC 版本匹配的 **[Carpet](https://modrinth.com/mod/carpet)**。

3. **用 Fabric 启动**  
   - 在启动器里选择 **Fabric** 配置文件启动游戏，**不要用原版**。

4. **存档与权限**  
   - 使用采样命令需 **在原版意义上为 OP**（`ops.json` 或 **`/op <你的名字>`**），或已被 OP 写入 **`config/sguprofiler_command_whitelist.json`**。仅「开作弊」但**未** OP、也不在白名单内，则**不能**使用命令（已取消“命令权限 ≥2 即可”的放宽逻辑）。

5. **进游戏后**  
   - 在聊天输入命令，例如：`/SGUProfiler profile start`，结束：`/SGUProfiler profile stop`（根字面量区分大小写，须与 **`SGUProfiler`** 一致）。  
   - 配置：`config/sguprofiler.json`；命令白名单：`config/sguprofiler_command_whitelist.json`（路径均在 `.minecraft/config/`）。

若进档报错或命令无反应，先确认 **jar 的 `+mc…` 与游戏版本一致**、**Fabric API 已装**，并查看 `logs/latest.log` 是否加载了 SGUProfiler。

## 构建（Stonecutter）

模组 id / 版本号写在 **`stonecutter.properties.toml`**（根字段 `mod.*`）；各 MC 的 Yarn、Fabric API、Carpet 写在对应 **`["x.y.z"]`** 段。

- **单独某一版本**（示例 1.21.6）：

```bash
./gradlew :1.21.6:build
```

- **一次构建全部支持版本并汇总 jar**（输出到 **`build/libs/<mod_version>/`**，例如 `build/libs/0.3.2/`）：

```bash
./gradlew buildAndCollect
```

本地开发可用 **`stonecutter.gradle.kts`** 中的 `stonecutter active "…"` 与 Gradle 任务 **「Set active project to …」** 切换 IDE 当前版本（说明见 [Stonecutter 入门](https://stonecutter.kikugie.dev/wiki/start/)）。跨小版本的原版 API 差异集中在 **`McCompat`**，用「方法签名」反射适配，避免源码中写 `//?` 与 active 子工程冲突。

根目录 **`gradle.properties`** 仅保留 JVM 等 Gradle 设置；**勿**再在其中写 `minecraft_version`（已由 Stonecutter 管理）。

## 配置 `config/sguprofiler.json`

位于**服务端** Fabric 配置目录；若不存在会在首次启动时写入模板。

| 字段 | 说明 |
|------|------|
| `permissionFallbackLevel` | **非玩家**执行采样命令所需的最低权限等级（如控制台），默认模板为 `4`。 |
| `sampleEveryNTicks` | 采样步长（每 N 刻考察一次，≥ 1）。 |
| `tickSampleMode` | `STRIDE_ONLY` / `HEAVY_ONLY` / `STRIDE_OR_HEAVY` / `STRIDE_AND_HEAVY`（重刻相关模式需将 `heavyLastTickMsThreshold` 设为**正数**）。 |
| `heavyLastTickMsThreshold` | 前一整刻墙钟耗时（毫秒）超过此值视为“重刻”判定用。 |
| `minProfileNanoseconds` | 低于此时长的切片不计入（0 表示不截断）。 |
| `autoMsptThreshold` | 若设置为大于 0，平滑 MSPT 超过该值时自动开始一段采样。 |
| `autoProfileDurationTicks` | 自动 MSPT 采样持续刻数。 |
| `scheduledProfileIntervalMinutes` / `scheduledProfileDurationTicks` | 定时自动采样（分钟间隔为 0 则关闭）。 |
| `autoCooldownTicks` | 自动 MSPT 采样结束后的冷却刻数。 |

## 命令使用者白名单

非 OP 玩家要被允许使用 **`/SGUProfiler profile …`**，需将其 UUID 写入：

**`config/sguprofiler_command_whitelist.json`**  
格式：UUID 的 JSON 数组，例如 `["xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"]`。

**维护白名单**仅 **OP**，或控制台 **权限等级 ≥ 4**：

| 指令 | 说明 |
|------|------|
| `/SGUProfiler whitelist add <玩家>` | 添加**当前在线**玩家的 UUID。 |
| `/SGUProfiler whitelist remove <玩家>` | 移除（目标需**在线**，按 UUID 匹配）。 |
| `/SGUProfiler whitelist list` | 列出白名单（有用户缓存时显示名，否则 UUID）。 |
| `/SGUProfiler whitelist clear` | 清空。 |

`<玩家>` 与原版一致（如 `Steve`、`@p`）。

## 采样命令

根命令：**`/SGUProfiler profile`**

| 子命令 | 说明 |
|--------|------|
| `start` | 全部分项采样。 |
| `start overworld` / `nether` / `end` / `all` | 仅统计指定维度。 |
| `start bot` | 仅 Carpet 假人 ActionPack 相关分项。 |
| `start bot <维度…>` | 带维度过滤的 bot 模式。 |
| `stop` | 结束采样并输出报告；表内为 **实体分项的 ms/采样刻均值**，与 F3/整服 MSPT 含义不同。 |

**OP** 始终可用；其它玩家仅当其在 **`sguprofiler_command_whitelist.json`** 中时可用。

## 单人存档与「无效的玩家数据」

本模组为 **服务端侧**（`environment: server`），单人世界由**内置服务端**加载即可。命令注册已改为 Fabric 的 `CommandRegistrationCallback`，避免在错误时机往命令分发器里注册命令导致的异常。

若仍出现「无效的玩家数据」，请先**备份存档**，再排查：是否把多人服 `playerdata` 与单机 `level.dat` 混用、是否跨大版本搬档、或其它模组/DataFix 问题；也可暂时移出本模组对比是否仍复现。

## 许可证

MIT — 见 [LICENSE](LICENSE)。
