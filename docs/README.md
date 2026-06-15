# ZstdNet 文档 · Docs

**ZstdNet** 给 Minecraft 联机透明压缩流量（基于 ZSTD），省公网带宽、减少卡顿；
支持 mod 端（Forge / NeoForge / Fabric）、插件端（Bukkit / Spigot / Paper / Purpur）与混合端（Arclight / Mohist 等）。

> ZstdNet transparently compresses Minecraft multiplayer traffic (ZSTD) to save public bandwidth and reduce lag.
> Works on mods (Forge / NeoForge / Fabric), plugins (Bukkit / Spigot / Paper / Purpur), and hybrid servers.

## 从哪开始 · Where to start

| 你是… / You are… | 看这个 / Read this |
| --- | --- |
| **新手 / 没装过 mod 或开过服** | 👉 [新手使用指南（中文）](getting-started.zh-CN.md) |
| **Beginner / never installed a mod or run a server** | 👉 [Getting Started (English)](getting-started.en-US.md) |
| 进阶用户，想看完整参数手册（中文） | [`../README.zh-CN.md`](../README.zh-CN.md) |
| Advanced users, full reference manual (English) | [`../README.en-US.md`](../README.en-US.md) |
| 想给项目新增一个加载器/版本变体 | [`../ADDING_A_VARIANT.md`](../ADDING_A_VARIANT.md) |

## 一分钟看懂该装哪个 · 1-minute "what to install"

| 你的角色 / Role | 装什么 / Install |
| --- | --- |
| 玩家进 ZstdNet 服 / Player joining a server | 客户端 mod / the client mod |
| Forge / NeoForge / Fabric 服 | 服务端同款 mod（丢进 `mods/`） / the same mod in `mods/` |
| Paper / Spigot / Purpur 插件服 | 插件版 `zstdnet-bukkit-*.jar`（丢进 `plugins/`） / the plugin in `plugins/` |
| Arclight / Mohist 混合端 | 插件版或 mod 版皆可，推荐插件版 / plugin or mod, plugin recommended |

详细步骤见上面的新手指南。/ See the Getting Started guides above for step-by-step instructions.

## 深入指南 · In-depth guides（中文）

[新手使用指南](getting-started.zh-CN.md) 用最少步骤带你跑起来；想看**手把手详解、原理、进阶调优**，看下面这几篇：

| 主题 | 文档 |
| --- | --- |
| 玩家：装客户端、连服、确认生效（更详细） | 👉 [玩家怎么用](for-players.md) |
| 服主：mod 服 / 插件服 / 单机开房（更详细） | 👉 [开服主怎么用](for-server-owners.md) |
| 省更多流量：字典一键、**实体/生物优化 transform**、压缩等级、LDM | 👉 [省流量进阶设置](save-bandwidth.md) |
| 遇到问题：进不去 / 没生效 / 闪退 / 语音 等排错 | 👉 [常见问题与排错](faq.md) |

> 这些深入篇目前为中文。English readers: see the bilingual root manual [`../README.en-US.md`](../README.en-US.md).

## 专题文档 · Topic guides

面向具体场景的专题文档。/ Guides for specific scenarios.

### 语音 mod · Voice mods

零配置兼容 Simple Voice Chat、Plasmo Voice 等语音 mod 的小白向使用指南：
A beginner-friendly guide to using voice mods (Simple Voice Chat, Plasmo Voice, …) with ZstdNet:

- 🗣️ **[语音 mod 使用指南（中文）](voice.zh-CN.md)**
- 🗣️ **[Voice Mod Guide (English)](voice.en-US.md)**
