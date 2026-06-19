# 内置正版（online-mode）账号验证 — 实现状态与说明

ZstdNet 现在可以**内置**「正版账号验证」，让正版玩家在离线后端上保留真实 UUID / 皮肤，**无需再额外安装 TrueUUID 等 mod**。

> **当前进度**：核心 + 自动检测 + **Fabric 三个变体（1.20.1 / 1.21.1 / 26.1）已实现并通过编译验证**；
> **Forge / NeoForge 六个变体尚未实现**（见下「待办」）。在未实现的加载器上，行为与历史版本完全一致——
> 绝不会在「无法验证」的情况下擅自把后端切成离线（不会静默丢失正版身份）。

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

| 变体 | 登录挂钩 | 构建验证 | 运行验证 |
|---|---|---|---|
| 共享核心 `mods/common`（协议/验证器/MiniJson/配置/A0/状态） | — | ✅ 全工具链编译 + 单测通过 | — |
| Fabric 1.20.1 | ✅ | ✅ `build` 通过 | ⏳ 待实测 |
| Fabric 1.21.1 | ✅ | ✅ `build` 通过 | ⏳ 待实测 |
| Fabric 26.1 | ✅ | ✅ `build` 通过 | ⏳ 待实测 |
| Forge 1.18.2 | ❌ 待实现 | （仅共享核心，随 ForgeGradle 编译） | — |
| Forge 1.19.2 | ❌ 待实现 | （同上） | — |
| Forge 1.20.1 | ❌ 待实现 | ✅ `compileJava+test`（共享核心） | — |
| NeoForge 1.20.1 | ❌ 待实现（复用 forge-1.20.1 的 java/资源） | （随 forge-1.20.1） | — |
| NeoForge 1.21.1 | ❌ 待实现 | ✅ `compileJava+test`（共享核心） | — |
| NeoForge 26.1 | ❌ 待实现 | ✅ `compileJava+test`（共享核心） | — |

> 所有变体目前都只是**编译验证**；运行正确性（真实正版账号进服拿到正确 UUID/皮肤、压缩仍生效、盗版客户端按策略处理）需在真实环境实测。

### 已落地的文件（Fabric + 共享）
- 共享：`mods/common/.../auth/PremiumAuthProtocol.java`、`MojangPremiumVerifier.java`、`MiniJson.java`（零依赖 JSON）、`PremiumAuthState.java`；`server/ServerProxyConfigFile.java`（新键+模板）、`server/DedicatedServerAutoPort.java`（A0 自动检测）；`platform/Platform.java`（新增 `supportsPremiumVerification()`，默认 false）。
- Fabric ×3：`mixin/ServerLoginPacketListenerImplAccessor.java`、`network/PremiumAuthSync.java`、`platform/FabricPlatform.java`(override→true)，并登记进 `zstdnet.mixins.json`、接线进 `Zstdnet`/`ZstdnetClient`。
- 单测：`mods/common/src/test/java/.../auth/`（协议/验证器/状态/MiniJson）。

---

## 4. 待办：Forge / NeoForge 实现指南

Forge/NeoForge 用 coremod（ASM），没有 Fabric 那套登录网络 API；且 **NeoForge 1.20.2+（1.21.1/26.1）砍掉了登录 wrapper**，其网络 API 指向 *configuration* 阶段（在 `LoginSuccess` **之后**，来不及改 UUID）。故这些变体需**直接拦截 vanilla 登录类**。

**实现步骤（每个变体新增 `coremod/PremiumAuthHooks.java` + coremod JS，并接线）：**

1. 客户端登录挂钩：注入 `net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl` 的「处理 login 自定义查询」路径，识别 `zstdnet:auth` → 调 `joinServer` → 回 `ServerboundCustomQueryAnswerPacket`。
2. 服务端登录挂钩：注入 `net.minecraft.server.network.ServerLoginPacketListenerImpl`：
   - **现代（1.21.1/26.1）流程**：状态机 `HELLO → (offline) startClientVerification → VERIFYING → tick() → verifyLoginAndFinishConnectionSetup → finishLoginAndWaitForClient(LoginSuccess)`。在 `verifyLoginAndFinishConnectionSetup` HEAD 拦截：首次进入时发 `ClientboundCustomQueryPacket`、把 state 改成 `NEGOTIATING`、记 pending、取消本次；在 `handleCustomQueryPacket(ServerboundCustomQueryAnswerPacket)` HEAD 拦截我方 transactionId：核验→成功则 set `authenticatedProfile`、state 改回 `VERIFYING`（让 tick 再次进入并放行），失败则按策略 `disconnect`。
   - **旧（1.18.2/1.19.2/1.20.1）流程**：字段名是 `gameProfile`（非 `authenticatedProfile`），状态枚举/方法名不同；亦可用 Forge `SimpleChannel` 的 login 包（`markAsLoginPacket()`/`loginIndex`）+ `PlayerNegotiationEvent` 延迟，再用 accessor 换 `gameProfile`。
   - GameProfile 替换需 coremod accessor（参考已落地的 Fabric `ServerLoginPacketListenerImplAccessor`）。
3. `Platform#supportsPremiumVerification()` 在该变体的 `*Platform` 里 override 返回 `true`（这样 A0 才会对该变体启用）。

**已核验的各版本符号（省去重新调研）：**

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
