# 内置正版（online-mode）账号验证 — 实现状态与说明

ZstdNet 现在可以**内置**「正版账号验证」，让正版玩家在离线后端上保留真实 UUID / 皮肤，**无需再额外安装 TrueUUID 等 mod**。

> **当前进度**：核心 + 自动检测 + **全部 9 个模组变体已实现并通过构建验证**——
> Fabric（1.20.1 / 1.21.1 / 26.1，走 fabric-api 登录网络）与 **Forge / NeoForge（1.18.2 / 1.19.2 / 1.20.1
> Forge + 1.20.1 / 1.21.1 / 26.1 NeoForge，走 coremod ASM）**。**Bukkit 插件端仍不支持**（独立代理无法挂钩 MC 登录）。
> 所有变体目前均为**构建验证**（编译 + 单测 + 全量打包），运行正确性仍待真实环境实测。

---

## 1. 为什么这样设计（机制）

ZSTD 只能压缩明文。原版 online-mode 的加密握手（`EncryptionRequest`/`Response`）一旦发生，登录后整条流量变 AES 密文，压缩收益归零。

因此本功能**不走加密握手**，而是仿 [TrueUUID](https://modrinth.com/mod/trueuuid)：在**登录阶段**用一条 login 自定义查询（信道 `zstdnet:auth`）做带外验证——

1. 服务端在 `LoginStart` 之后、`LoginSuccess` 之前，发一条带一次性 `nonce` 的查询。
2. ZstdNet 客户端 mod 据 `nonce` 推导 `serverId`，本地调用 Mojang 会话服务 `joinServer`（**access token 不出客户端**），回包告知是否完成。
3. 服务端用同一 `serverId` 调 `hasJoined` 核验，拿到**真实正版 UUID + 皮肤**，在 `LoginSuccess` 之前替换登录档案。

全程不开 AES → 明文不变 → **ZSTD 压缩照常**。后端保持 `online-mode=false` 运行（由自动检测在内存里保证）。

**安全模型**：`serverId` 即服务端一次性 `nonce`，绑定本次登录、单用，足以防重放与冒名。因不复用原版加密握手对服务端公钥的绑定，理论上不抵御「用户主动连接到的恶意服务端」发起的会话转发——这是任何「离线后端 + 登录阶段校验」方案（含 TrueUUID）的固有取舍，且本功能为可选 opt-in、默认仅在 `online-mode=true` 时启用。

---

## 2. 配置（`config/zstdnet-server.properties`）

| 键 | 取值 | 默认 | 说明 |
|---|---|---|---|
| `premium_verification` | `auto` / `on` / `off` | `auto` | 总开关。`auto` **跟随 `server.properties` 的 `online-mode`**：`online-mode=true` 即开启验证；`online-mode=false` 即纯离线。`on`/`off` 手动强制。 |
| `premium_verification_mode` | `lenient` / `strict` | `lenient` | 验证不通过（盗版/没装本 mod/客户端离线会话）时：`lenient`=回落离线 UUID 照常进服；`strict`=拒绝。与总开关正交。 |
| `premium_session_server` | URL / 空 | 空(=Mojang) | 会话服务基址，可对接 authlib-injector / 自建 Yggdrasil。 |
| `premium_pass_real_ip` | `true`/`false` | `false` | 核验时是否把玩家真实 IP 交给会话服（类 prevent-proxy-connections）。 |

### 自动检测（A0，`DedicatedServerAutoPort`）
- 解析三态总开关 + `online-mode` → 得到 `verificationEnabled`。
- **仅当本加载器变体实现了登录挂钩**（`Platform#supportsPremiumVerification()` 为 true，目前仅 Fabric）时才会启用。
- 启用时：在**内存**里把后端 `online-mode` 置 false（原版加密不触发，压缩照常）+ 接管压缩阈值。
  **绝不回写磁盘 `server.properties` 的 `online-mode`**——磁盘上的值保留作为下次启动 `auto` 判定的「管理员意图」信号。
- 时机正确性：钩子在 `DedicatedServer.initServer()` 读取 props 之后、`setUsesAuthentication(props.onlineMode)` 之前替换该局部变量（与现有 server-port/压缩阈值覆盖同一机制）。

---

## 3. 实现状态

| 变体 | 登录挂钩 | 机制 | 构建验证 | 运行验证 |
|---|---|---|---|---|
| 共享核心 `mods/common`（协议/验证器/MiniJson/配置/A0/状态） | — | — | ✅ 全工具链编译 + 单测通过 | — |
| Fabric 1.20.1 | ✅ | Mixin + fabric-api 登录网络 | ✅ `build` 通过 | ⏳ 待实测 |
| Fabric 1.21.1 | ✅ | 同上 | ✅ `build` 通过 | ⏳ 待实测 |
| Fabric 26.1 | ✅ | 同上 | ✅ `build` 通过 | ⏳ 待实测 |
| Forge 1.18.2 | ✅ | coremod ASM（旧版流程） | ✅ `build` 通过 | ⏳ 待实测 |
| Forge 1.19.2 | ✅ | coremod ASM（旧版流程） | ✅ `build` 通过 | ⏳ 待实测 |
| Forge 1.20.1 | ✅ | coremod ASM（旧版流程） | ✅ `build` 通过 | ⏳ 待实测 |
| NeoForge 1.20.1 | ✅（复用 forge-1.20.1 的 java/资源/Platform） | coremod ASM（旧版流程） | ✅ `build` 通过 | ⏳ 待实测 |
| NeoForge 1.21.1 | ✅ | coremod ASM（现代流程） | ✅ `build` 通过 | ⏳ 待实测 |
| NeoForge 26.1 | ✅ | coremod ASM（现代流程） | ✅ `build` 通过 | ⏳ 待实测 |

> 所有变体目前都只是**构建验证**（编译 + 单测 + 全量打包，含 Forge `reobf` 与 NeoForge `shadow`，且确认 jar 内打入了
> `coremods/zstdnet_premium_auth.js`、`coremods.json` 登记与两个 hook 类）；运行正确性（真实正版账号进服拿到正确
> UUID/皮肤、压缩仍生效、盗版客户端按策略处理）需在真实环境实测。

### 已落地的文件
- 共享：`mods/common/.../auth/PremiumAuthProtocol.java`（含 coremod 路线的 `channelPathWithServerId`/`serverIdFromChannelPath`/`COREMOD_TRANSACTION_ID`）、`MojangPremiumVerifier.java`、`MiniJson.java`（零依赖 JSON）、`PremiumAuthState.java`；`server/ServerProxyConfigFile.java`（新键+模板）、`server/DedicatedServerAutoPort.java`（A0 自动检测）；`platform/Platform.java`（新增 `supportsPremiumVerification()`，默认 false）。
- Fabric ×3：`mixin/ServerLoginPacketListenerImplAccessor.java`、`network/PremiumAuthSync.java`、`platform/FabricPlatform.java`(override→true)，并登记进 `zstdnet.mixins.json`、接线进 `Zstdnet`/`ZstdnetClient`。
- Forge/NeoForge ×（5 套源码，nf1201 复用 forge1201）：`coremod/PremiumAuthServerHooks.java`、`coremod/PremiumAuthClientHooks.java`、`resources/coremods/zstdnet_premium_auth.js`（登记进 `META-INF/coremods.json`）、`platform/*Platform.java`(override→true)。**coremod 是声明式的，无需在 `Zstdnet`/`ServerProxyBootstrap` 接线**。
- 单测：`mods/common/src/test/java/.../auth/`（协议/验证器/状态/MiniJson）。

---

## 4. Forge / NeoForge 实现说明（coremod ASM）

Forge/NeoForge 没有 Fabric 那套登录网络 API；且 **NeoForge 1.20.2+（1.21.1/26.1）砍掉了登录 wrapper**，其网络 API 指向 *configuration* 阶段（在 `LoginSuccess` **之后**，来不及改 UUID）。故这些变体**用 coremod（ASM）直接拦截 vanilla 登录类**——与已有的三处补丁点（连接接管 / LAN 压缩阈值 / 真实 IP）同一机制（`resources/coremods/*.js` + `META-INF/coremods.json` → 调静态 hook 类）。

**两套模板**（按登录流程分；`coremods/zstdnet_premium_auth.js` 各含 2 个 transformer：server + client）：

- **旧版流程（Forge 1.18.2 / 1.19.2 / 1.20.1，NeoForge 1.20.1 复用）**：
  - 服务端门控注入 `handleAcceptedLogin()`（用「**唯一构造 `ClientboundGameProfilePacket` 的 `()V` 方法**」结构特征定位，避免依赖被混淆的方法名）首部：`if (!beforeFinalizeLogin(this)) return;`。早返回时 state 仍为 `READY_TO_ACCEPT`，`tick()` 下一 tick 再次调用 → 天然轮询；原版 600-tick 慢登录超时仍生效。
  - 服务端应答拦截注入 `handleCustomQueryPacket(ServerboundCustomQueryPacket)`（按描述符唯一定位）首部，识别我方事务号。
- **现代流程（NeoForge 1.21.1 / 26.1）**：
  - 服务端门控注入 `verifyLoginAndFinishConnectionSetup(GameProfile)`（用「**唯一构造 `ClientboundLoginCompressionPacket` 的 `(GameProfile)V` 方法**」定位，区别于同描述符的 `startClientVerification`/`finishLoginAndWaitForClient`）首部。早返回时 state 仍为 `VERIFYING` → tick 轮询。
  - 服务端应答拦截注入 `handleCustomQueryPacket(ServerboundCustomQueryAnswerPacket)`。
- **客户端（两套通用）**：注入 `ClientHandshakePacketListenerImpl#handleCustomQuery`（按描述符唯一定位）首部，识别信道 `zstdnet:auth/<serverId>` → 后台 `joinServer` → 回空应答。

**关键设计点**：
- **nonce 走信道 `ResourceLocation` 路径**（`zstdnet:auth/<hex>`），非 payload——现代 MC 解码 login 查询时丢弃 payload 字节（`DiscardedQueryPayload`/`skipBytes`），但信道 RL 始终随包读出；`hex` 是 RL path 合法字符。服务端发送用 `new DiscardedQueryPayload(rl)`（write 为空）。见 `PremiumAuthProtocol.channelPathWithServerId/serverIdFromChannelPath`。
- **应答内容服务端读不到**（现代 payload 同样被丢弃），故服务端不依赖应答字段，而是「收到我方事务号应答」即触发 `hasJoined` 核验——客户端保证先 `joinServer` 再发应答，核验结果为唯一真源。事务号用固定魔数 `COREMOD_TRANSACTION_ID`（避开 Forge 顺序登录索引）。
- **不改 vanilla 登录 state**：门控仅靠 pending 表早返回 + tick 轮询，无需把 state 改成 `NEGOTIATING` 再改回（避免依赖枚举常量名）。
- **GameProfile / Connection 字段按类型反射**（非按名），对 Forge SRG 运行时与 NeoForge 官方映射运行时都稳健；旧版 `gameProfile` 与现代 `authenticatedProfile` 均为唯一的 `GameProfile` 类型字段。
- `Platform#supportsPremiumVerification()` 在各 `*Platform` override 返回 `true`，A0 才对该变体启用。
- Forge 服务端 `handleCustomQueryPacket` 走 `NetworkHooks.onCustomPayload`（对 `ServerboundCustomQueryPacket`，`getName()==LoginWrapper.WRAPPER`），故必须 HEAD 拦截我方事务号并 `return`，否则会落入 Forge 的 LoginWrapper 处理。

**已核验的各版本符号（实现依据）：**

| 项 | 1.20.1 (authlib 4.0.43) | 1.21.1 (authlib 6.0.54) | 26.1 (authlib 7.0.63, 非混淆) |
|---|---|---|---|
| 登录档案字段 | `gameProfile` | `authenticatedProfile` | `authenticatedProfile` |
| `joinServer(...)` | `(GameProfile, token, serverId)` | `(UUID, token, serverId)` | `(UUID, token, serverId)` |
| GameProfile | 类，`getName()/getProperties()` | 类，`getName()/getProperties()` | **record**，`name()/properties()/id()` |
| 客户端取会话服 | `Minecraft.getMinecraftSessionService()` | `Minecraft.getMinecraftSessionService()` | `Minecraft.services().sessionService()` |
| `ResourceLocation` | `new ResourceLocation(ns,path)` | `ResourceLocation.fromNamespaceAndPath` | **改名 `Identifier`**，`Identifier.fromNamespaceAndPath` |
| `User` 取 UUID | `getProfileId()` | `getProfileId()` | `getProfileId()` |

> Forge/NeoForge 同样会撞 Mohist 自带 zstd-jni 的 JPMS 问题（见 `mohist-zstd-relocation` 记忆），与本功能无关，按既有计划处理。

---

## 5. 边界 / 注意

- **玩家档案迁移**：开启后被验证玩家从离线 UUID（`OfflinePlayer:<name>`）切到正版 UUID，原有离线 `playerdata` 不会自动加载（与任何离线→在线切换同理）。建议在新服或可接受重置时启用；后续可加「按名映射」一次性迁移。
- 依赖两端都装 ZstdNet（本就是硬要求）；原版/盗版客户端不应答查询 → 按 `premium_verification_mode` 处理（默认宽松=离线进服）。
- `premium_session_server` 可对接外置登录；`premium_pass_real_ip` 默认关（CGNAT 等场景 IP 可能不一致）。
