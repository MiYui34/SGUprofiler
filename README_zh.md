# SGUProfiler

基于 Fabric 的**服务端**模组，按实体统计刻上耗时（AI、实体 Tick、位移/传送、碰撞等；安装 Carpet 时还可拆分假人 **Attack** / **Use** / 其余 ActionPack 时间）。结果通过 [Carpet](https://github.com/gnembon/fabric-carpet) 的 `Messenger` 在**游戏内聊天**中着色输出。

**[English](README.md)**

## 环境要求

| | 版本 |
|--|--|
| Minecraft | 1.21.x（以 `gradle.properties` 为准） |
| Java | 21+ |
| [Fabric Loader](https://fabricmc.net/) | ≥ 0.16.0 |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 与游戏版本匹配 |

**可选：** [Carpet](https://github.com/gnembon/fabric-carpet) — 使用 `profile … bot` 及假人攻击/交互等分项时需安装，版本需与当前 MC 兼容。

## 构建

```bash
./gradlew build
```

生成的 jar：`build/libs/sguprofiler-<版本>.jar`（版本见 `gradle.properties`）。

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

**OP** 始终可用；普通玩家需 **OP** 或 **白名单**（见上节）。

## 许可证

MIT — 见 [LICENSE](LICENSE)。
