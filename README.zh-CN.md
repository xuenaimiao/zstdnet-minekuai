# ZstdNet

> 🚀 **第一次用 / 没装过 mod 或开过服？** 先看 **[新手使用指南（零基础）](docs/getting-started.zh-CN.md)** —— 大白话手把手教你装好、进服、开服。全部文档见 [`docs/`](docs/README.md)。

ZstdNet 是一个 Minecraft Java 版模组，用 ZSTD 压缩客户端与服务端之间的转发流量，目标是在高重复数据场景下显著降低公网带宽占用。

它尤其适合：

- 机械动力类服务器
- 大型整合包服务器
- 需要走 FRP / 内网穿透 / 隧道转发的联机场景
- 单机开房后希望给朋友提供更省带宽入口的场景

## 这个模组能做什么

- 客户端输入 ZstdNet 地址后，自动在本地启动临时代理并接管连接
- 服务端提供独立的 Zstd 入口，把压缩流量转发到后端 Minecraft 端口
- 支持专用服，也支持单机开放局域网后的房主使用
- 自带 HUD，可在游戏内查看当前是否正在走 zstd，以及实时流量情况
- 支持原版状态查询透传，方便服务器列表正常 ping
- 客户端本地代理会在同一个本地端口提供 UDP 原样透传，用于兼容 Sable / 机械动力：航空学这类依赖 Minecraft 同端口 UDP 的模组
- **零配置兼容语音模组**：自动探测后端 Simple Voice Chat / Plasmo Voice 监听的独立 UDP 端口并一起接管，玩家进服后客户端自动开监听，服主无需手填端口（mod 服与插件端都适用）

## 实际效果

这个模组设计的重点，就是降低高重复数据包带来的公网带宽消耗。

例如在大型整合包[齿轮盛宴官方网站](https://www.xn--dctt54dhmrbwo.com)中，压缩收益通常会非常明显。下面是一组服务器侧的实际统计示例：

```text
Raw: 189.06 GB (3.6MB/s) | Zstd: 10.28 GB (252.7KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (5.0MB/s) | Zstd: 10.28 GB (234.3KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (3.2MB/s) | Zstd: 10.28 GB (215.5KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.07 GB (4.8MB/s) | Zstd: 10.28 GB (303.7KB/s) | Ratio: 5.44% | Conns: 8
```

**Ratio 越低，说明压缩后的实际传输量越小。**

## 安装说明

推荐客户端和服务端都安装本模组。

- 当前仓库已同步支持：
  Forge 1.18.2、Forge 1.19.2、Forge 1.20.1、NeoForge 1.20.1、NeoForge 1.21.1、Fabric 1.20.1、Fabric 1.21.1、NeoForge 26.1、Fabric 26.1
  （26.1 构建覆盖整个 26.1.x 线，即 26.1.1 / 26.1.2；需要 Java 25）
  （1.18.2 为 Forge 专属；其实体级变换收益自动退化为版本无关的去交错，详见下文「实体包流变换」）
- **插件端（无需 mod 加载器）**：Bukkit / Spigot / Paper / Purpur，以及 Arclight / Mohist 等混合端 —— 详见下方「[插件端 / 混合端](#插件端--混合端bukkit--spigot--paper--arclight--mohist)」
- 普通连接远程 ZstdNet 服务器时：客户端需要安装
- 使用内置 Zstd 服务端入口时：服务端需要安装
- 单机开放局域网并对外分享 Zstd 入口时：房主客户端需要安装

## 普通玩家怎么连接

如果服主使用默认推荐的 `auto_takeover=true`，那么玩家通常继续填写服主原来公开的那个地址和端口即可，不需要额外学习一套新端口。

例如：

```text
play.example.com:25565
1.2.3.4:25565
```

也就是说在默认自动接管模式下：

- 玩家继续连接 `server.properties` 里的公网端口
- ZstdNet 会自动接管这个端口
- 后端 Minecraft 会被自动挪到另一个本地端口

只有当服主手动关闭 `auto_takeover`，改成手动模式时，玩家才需要连接 `listen` 里单独配置的 Zstd 端口，比如：

```properties
auto_takeover=false
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

## 必要准备

在专用服上使用内置 ZstdNet 服务端入口之前，请先确认后端 Minecraft 服务器已经正确配置。

在服务器的 `server.properties` 中至少设置：

```properties
online-mode=false
```

- `online-mode=false`：关闭后端服务器的原版正版验证
- `network-compression-threshold=1048576`：专用服启用内置 ZstdNet 服务端入口后，模组会在启动时自动接管这一项，通常不需要再手动填写

如果后端服务器继续启用原版验证，连接可能失败；如果原版网络压缩没有被 ZstdNet 接管，压缩收益也会明显低于预期。

### 内置正版验证（无需额外装 mod）

ZstdNet 现在可以**内置**正版账号验证：在登录阶段用一条自定义查询让客户端本地向 Mojang 证明账号所有权（access token 不出客户端），服务端核验后保留真实正版 UUID/皮肤——全程不开加密，压缩照常。这样在离线后端上也能验证正版，**无需再额外安装 TrueUUID 等 mod**。

- 配置项 `premium_verification`（`config/zstdnet-server.properties`）默认 `auto`：**跟随 `server.properties` 的 `online-mode`**。也就是说，你照常把想要正版验证的服设成 `online-mode=true` 即可——ZstdNet 会自动开启内置验证，并在运行时把后端切到离线以保住压缩（**不改你磁盘上的 `online-mode`**）。`on`/`off` 可手动强制。
- `premium_verification_mode`：`lenient`（默认，正版玩家拿真实 UUID、其余离线进服）/ `strict`（仅允许正版）。
- 也支持对接 authlib-injector / 自建 Yggdrasil（`premium_session_server`）。

> **当前仅 Fabric 变体（1.20.1 / 1.21.1 / 26.1）内置了该功能**；Forge / NeoForge 待实现（详见仓库 `PREMIUM_VERIFICATION.md`）。在尚未支持的加载器上，请仍按上面把后端设 `online-mode=false`（如需保留正版身份可搭配 [TrueUUID](https://www.curseforge.com/minecraft/mc-mods/trueuuid)）；ZstdNet 不会在无法验证时擅自把后端改成离线。
>
> 注意：开启内置验证后，被验证玩家会用正版 UUID，原离线 `playerdata` 不会自动迁移（与任何离线→在线切换相同）。

## 专用服怎么配置

首次启动后，会在 `config` 目录生成：

```text
config/zstdnet-server.properties
```

最常见的配置示例：

```properties
enabled=true
auto_takeover=true
```

含义：

- `auto_takeover=true`：启动时读取 `server.properties` 里的 `server-port`，直接把它接管成公网入口
- `listen` / `target`：自动模式下由 ZstdNet 在运行时内部解析，通常不需要手填

也就是说：

- 玩家继续连接原来的公网端口
- ZstdNet 会在内部自动接管并转发到后端端口

如果你前面还有 FRP、HAProxy、内网穿透或其他转发层，请确保公网入口最终转发到 `listen`，不要直接转发到原版游戏端口，否则会绕过 zstd。

如果整合包包含 Sable / 机械动力：航空学等依赖同端口 UDP 的模组，也请确保前置转发层同时转发同一个端口的 TCP 和 UDP。ZstdNet 会在客户端本地 `127.0.0.1:<本地代理端口>` 上同时监听 TCP 和 UDP，并把 UDP 原样转发到远端同端口；如果公网隧道只转发 TCP，Sable 会回退到性能较差的 TCP 管线。

**语音模组也零配置（mod 服）**：整合包带 Simple Voice Chat / Plasmo Voice 时，ZstdNet 会自动扫描 `config/voicechat/`、`config/plasmovoice/`，找出语音监听的独立 UDP 端口（SVC 默认 24454），玩家进服时把端口下发给客户端 mod，客户端自动在本机为这些端口开监听——服主无需手填任何端口。默认 `tunnel` 模式下**语音也复用入口端口这一个口**，不必单独放行语音端口（FRP / 内网穿透也只穿一个口即可）；如需让语音直连真实端口可改 `voice_transport=bridge`（那样要单独放行该 UDP 端口）。自动探测覆盖不到的冷门 UDP 模组，用 `extra_udp_ports` 手动补端口。语音流量本身不压缩、只做原样转发。完整说明与已知边界（`voice_host` / `[host.public].ip` 非默认会绕过等）见下方常见问题「[Simple Voice Chat / Plasmo Voice 这类语音模组能不能用](#simple-voice-chat--plasmo-voice-这类语音模组能不能用)」，零基础图文步骤见[语音 mod 使用指南](docs/voice.zh-CN.md)。

默认推荐直接使用 `auto_takeover=true`。这样模组会在启动时读取 `server.properties` 里的 `server-port`，把它作为公网入口 `listen`，再自动把后端 Minecraft 挪到另一个本地空闲端口 `target`，服主通常不需要再手动填写端口。

## 插件端 / 混合端（Bukkit / Spigot / Paper / Arclight / Mohist）

如果你的服务器是**插件端**（Paper / Spigot / Purpur，没有 mod 加载器）或**混合端**（Arclight / Mohist / CatServer 等同时吃 Forge mod 和 Bukkit 插件），可以用**插件版** `zstdnet-bukkit-<版本>.jar`。

**先说重要前提**：ZSTD 压缩天生需要两端配合。插件只是「服务端那一半」，**想要压缩的玩家仍然要安装现有的 ZstdNet 客户端 mod**（Forge/NeoForge/Fabric，一字不用改）。没装 mod 的原版玩家照常直连，不受影响，只是没有 ZSTD 压缩。

**和 mod 服的关键差别**：插件加载时 MC 服务器端口已经绑定，插件**没法**像 mod 那样把后端挪走、自己占用原端口。所以插件端用**独立监听端口**：

```text
原版/未装 mod 玩家  ──────────────►  MC 服务器 25565        （插件不碰这条路）
装了 ZstdNet 的玩家 ──► 插件代理 25566 ──(解压)──► 127.0.0.1:25565 MC 后端
```

**安装步骤**：

1. 把 `zstdnet-bukkit-<版本>.jar` 放进服务器的 `plugins/` 目录并启动。**同一个 jar 同时支持 1.20.1 与最新 1.21.x**。
2. 首次启动会生成 `plugins/ZstdNet/zstdnet-server.properties`，默认：
   ```properties
   enabled=true
   auto_takeover=false
   listen=0.0.0.0:25566      # zstd 代理对外入口（= server-port + 1）
   target=127.0.0.1:25565    # 本机 Minecraft 后端端口
   ```
3. 在防火墙 / 安全组放行 `listen` 端口（如 25566）。原版玩家继续用 25565。
4. 装了 ZstdNet 客户端 mod 的玩家，在服务器列表里把地址填成 `服务器IP:25566` 即享压缩；其余压缩等级、字典、限速等配置项与 mod 服完全一致（见下方「配置文件」）。

> 提示：`listen` 端口必须与 MC 的 `server-port` 不同，且空闲；需要的话自行改这一行。插件端请保持 `auto_takeover=false`。

**管理指令**（需要 `zstdnet.admin` 权限，默认 OP）：

```text
/zstdnet status   查看代理状态、监听/后端端口、连接数、压缩比
/zstdnet reload   重新读取配置并重启代理（改完配置也会在几秒内自动热重载）
/zstdnet start    启动代理
/zstdnet stop     停止代理
```

**语音模组也零配置**：插件端同样自动兼容 Simple Voice Chat / Plasmo Voice。插件会扫描 `plugins/voicechat/`、`plugins/PlasmoVoice/` 找出语音监听的独立 UDP 端口，玩家进服时把端口下发给 ZstdNet 客户端 mod（走 Bukkit 插件消息，对原版玩家无副作用），客户端自动在本机为这些端口开监听。默认 `tunnel` 隧道——**语音也走 `listen` 入口端口这一个口**，无需再单独放行语音端口；也可在配置里改 `voice_transport=bridge`。探测不到时可用 `extra_udp_ports` 手填。完整说明与边界见下方「[Simple Voice Chat / Plasmo Voice 这类语音模组能不能用](#simple-voice-chat--plasmo-voice-这类语音模组能不能用)」。

**混合端怎么选**：Arclight / Mohist / CatServer 等底层是 Forge/NeoForge，**既能放 mod 也能放插件**：

- 最省心：直接用本插件（丢进 `plugins/`），不依赖混合端对 coremod/自动接管的兼容性。
- 也可以用对应的 Forge/NeoForge **mod 版**（丢进 `mods/`）；但混合端可能改了端口初始化或网络栈，若 `auto_takeover` 改端口失败，请把 mod 版配置里的 `auto_takeover` 改为 `false` 并手填 `listen`/`target`，或直接改用插件版。

**关于真实 IP（frp / 反代场景）**：插件端默认情况下，经代理进来的玩家在后端看来是 `127.0.0.1`（本机回环）。绝大多数服务器无影响；但若你用 frp/反代并且需要后端按 IP 封禁 / 风控 / 地理定位，目前请用平台原生的 IP 转发（Velocity 现代转发 / BungeeCord / 服务端 `proxy-protocol`）。ZstdNet 插件端的「透明真实 IP 还原」需要按服务端版本做 netty 注入、影响面是整服网络，需在真实服上充分验证，作为后续增强项暂未随本版本提供。

## FRP 典型链路

推荐链路：

```text
玩家客户端 -> 公网 FRP 端口 -> 主机上的 ZstdNet listen 端口 -> 本地游戏端口
```

例如：

- 游戏端口：`25565`
- 公网入口：`25565`
- 后端游戏端口：`25566`（如果空闲会自动分配）
- FRP 公网端口：`25565`

那么推荐这样配置：

- 在 `zstdnet-server.properties` 中保持 `auto_takeover=true`
- 公网入口最终转发到当前 `listen`
- 后端 `target` 由 ZstdNet 在启动时自动分配
- 玩家最终继续连接原来的公网端口

## 真实 IP / FRP PROXY v2 怎么选

如果玩家是直接连到 zstdnet 入口端口，比如公网直连、局域网直连、虚拟局域网直连，就保持：

```properties
trust_proxy_protocol=false
```

这种情况下 zstdnet 会直接从 TCP 连接里拿到玩家 IP：公网直连看到玩家公网 IP，局域网 / 虚拟局域网直连看到玩家的内网或虚拟网 IP。

只有当前面还有 FRP / 反代，并且你希望后端看到玩家真实 IP 时，才开启：

```properties
trust_proxy_protocol=true
trusted_proxy_ips=127.0.0.1,::1,0:0:0:0:0:0:0:1
```

同时 FRP TCP 映射需要开启 PROXY Protocol v2，例如：

```toml
transport.proxyProtocolVersion = "v2"
```

`trusted_proxy_ips` 填的是“有资格告诉 zstdnet 玩家真实 IP 的前置代理机器 IP”，不是玩家 IP，也不是允许进服的网段。frpc 和服务端在同一台机器时保持默认即可；如果 frpc 在另一台机器，就填那台机器连接到本服务器时使用的内网 / 虚拟局域网 IP。

注意：`trust_proxy_protocol=true` 后，直接连 zstdnet 入口端口但不带 PROXY v2 头的连接会被拒绝，例如本机、局域网、虚拟局域网或公网直连 `listen` 端口都会进不去。这是为了防止别人伪造真实 IP。

## 单机 / 局域网开房

单机世界“对局域网开放”时，ZstdNet 会在界面中加入 Zstd 端口输入框，并兼容原版或高级联机界面里的游戏端口输入框。

推荐用法：

- 游戏端口通常可以留空，除非你明确需要固定本次 Minecraft 局域网端口；ZstdNet 会自动跟随本次实际开放的 LAN 端口。
- Zstd 端口优先使用配置里的端口；如果该端口被占用，或刚好被本次 LAN/语音端口占用，运行时会自动换到可用端口。
- 开放成功后，聊天框会提示本次实际使用的 Zstd 端口；提示里的 Zstd 端口可以点击复制。

当前生效设置会写入：

```text
config/zstdnet-server.properties
```

并支持热重载。
和专用服不同，这个场景下配置文件通常会直接写出当前偏好的 `listen` / `target`；如果运行时因为端口冲突自动换端口，请以聊天框提示和 `/zstdport show` 显示的实际端口为准。

如果朋友要从外网加入，请把下面这个地址发给对方：

```text
你的公网 IP 或域名:Zstd 端口
```

例如：

```text
mc.example.com:35565
203.0.113.10:35565
```

如果某个高级联机模组完全替换了开房界面，导致 Zstd 输入框不可见，可以用 `/zstdport show` 查看当前端口；只有需要固定公网/隧道端口时，才需要使用 `/zstdport zstd <端口>` 手动指定。

## 指令

### `/zstdhud`

用于查看或切换 HUD：

```text
/zstdhud
/zstdhud on
/zstdhud off
/zstdhud toggle
```

### `/zstdport`

用于查看或修改单机 / 局域网场景下的端口：

```text
/zstdport show
/zstdport game 25565
/zstdport zstd 35565
/zstdport voice 25565
/zstdport zstdvoice 24455
```

注意：

- `/zstdport` 是客户端指令
- `show` 可以查看当前配置
- `voice` 修改 `voice_chat_target`，也就是后端语音端口
- `zstdvoice` 修改 `voice_chat_listen`，也就是公网语音入口
- 单机开房 / LAN 模式下，默认 `voice_chat_target` 是 `127.0.0.1:25565`
- 专用服默认 `voice_chat_target` 仍然是 `127.0.0.1:24454`
- `game`、`zstd`、`voice`、`zstdvoice` 只有本地房主且有管理员权限时才能修改
- 专用服不会通过这个指令改服务器配置
- 专用服请直接修改 `config/zstdnet-server.properties`

## HUD 面板

开启 `zstdhud` 后，可以直接在游戏里看到：

- 当前连接模式
- 监听地址或远程目标地址
- 压缩后实时速率
- 原始实时速率
- 累计传输量
- 压缩率
- 当前连接数

如果你想确认当前到底有没有走 zstd，HUD 是最直观的判断方式。

## 常见问题

### 为什么我进不去服务器？

优先检查这些问题：

1. 如果 `auto_takeover=true`，玩家继续填写原来 `server.properties` 里的公网端口即可；如果 `auto_takeover=false`，再确认玩家填写的是不是配置里的 `listen` 端口。
2. FRP 或其他隧道是不是转发到了 `listen` 端口。
3. `config/zstdnet-server.properties` 里的 `enabled=true` 是否已设置。
4. `listen` 和 `target` 是否写反。
5. Zstd 端口是否已被其他程序占用。
6. 是否有其他模组拦截了登录或握手流程。
7. 局域网场景下，请优先使用聊天框提示的 Zstd 端口；游戏端口通常可以留空，因为 ZstdNet 会自动跟随本次实际 LAN 端口。

### 压缩率接近 100% 是不是没生效？

不一定。

有些流量本身就不适合继续压缩，或者已经被加密，收益会明显下降。

### 会不会和别的联机模组冲突？

有可能。

如果多人游戏菜单或“对局域网开放”界面被其他模组大幅改写，理论上仍然存在兼容性风险。

### Simple Voice Chat / Plasmo Voice 这类语音模组能不能用？

可以，而且是**零配置**的（**mod 服与插件端都适用**）。语音流量不压缩，只做原样转发。ZstdNet 会自动探测后端常见语音模组（Simple Voice Chat、Plasmo Voice）监听的独立 UDP 端口，并把它们一起接管，玩家进服后自动在客户端本机为这些端口开监听——无需你手动填任何端口。（插件端在 `plugins/voicechat/`、`plugins/PlasmoVoice/` 下探测，并用 Bukkit 插件消息把端口下发给客户端 mod。）

语音有两种传输方式，由服务端配置 `voice_transport` 控制：

- **`tunnel`（默认）**：语音 UDP 也走 ZstdNet 的公网入口端口（和游戏同一个端口），服务端按通道拆分后转给后端各语音端口。**这样你只需要对外放行入口端口一个口**，FRP / 内网穿透也只穿一个口即可。
- **`bridge`**：客户端在本机把语音 UDP 直连到「真实服务器的同一个端口」，服务端不额外中转。这种方式需要你**为该语音端口单独做公网放行 / 端口映射**。

其它说明：

- **同端口语音模组**（Sable / 机械动力：航空学，以及把 SVC 设成跟随服务器端口的情况）一直都能用——它们的 UDP 跟游戏走同一个端口，由内置 game 直通覆盖。
- **已知边界**：如果管理员把 Simple Voice Chat 的 `voice_host` 或 Plasmo 的 `[host.public].ip` 显式填成了某个公网地址，客户端会按你填的地址直连、**绕过 ZstdNet**（服务端会打印一条 WARN 提示）。要让 ZstdNet 接管语音，请把它们留默认（空 / `0.0.0.0`）。
- **其它 UDP 模组**：自动探测覆盖不到的，可以用 `extra_udp_ports` 手动补一份端口清单。
- 如果语音路由没起来，游戏本身的 TCP 连接仍然正常，只是语音会离线。
- 旧版 `voice_chat_listen` / `voice_chat_target` 仍然保留兼容；`/zstdport voice <端口>`、`/zstdport zstdvoice <端口>` 仍可手动调整。

> 📖 想要**手把手、零基础**的图文步骤（玩家怎么进服说话 / 服主怎么放行端口 / tunnel 与 bridge 怎么选 / 各类语音 mod 一览），见 **[语音 mod 使用指南（小白向）](docs/voice.zh-CN.md)**。

## 配置文件

- 客户端：`config/zstdnet-client.toml`
- 服务端：`config/zstdnet-server.properties`
- `zstdnet-server.properties` 会由模组自动维护；命令改端口或自动接管时，会按内置的带注释模板重新写回配置文件。

**配置项说明：**

- `enabled`：是否开启 ZstdNet 服务（默认：true）
  - 设为 true 才能使用 Zstd 压缩功能

- `auto_takeover`：是否自动读取 `server.properties` 里的 `server-port` 并接管为公网入口（默认：true）
  - 开启后，专用服通常不需要再手动填写 `listen` / `target`
  - ZstdNet 会在启动时自动把后端 Minecraft 挪到另一个本地空闲端口

- 专用服自动模式：配置文件通常不会固定写出 `listen` / `target`
  - `listen` 会在启动时解析成当前公网入口
  - `target` 会在启动时解析成自动分配的本地后端端口
  - 不要把运行中的 `target` 直接暴露到公网

- `listen`：手动模式下的 Zstd 压缩入口地址和端口
  - 当 `auto_takeover=false` 时，玩家连接游戏时用的就是这个地址和端口
  - 0.0.0.0 表示允许所有IP访问

- `target`：手动模式下后端 Minecraft 服务器的地址和端口
  - ZstdNet 会把压缩后的流量转发到这个地址
  - 127.0.0.1 表示本地服务器

- 单机 / 局域网开房：配置文件通常会直接保留 `listen` / `target`
  - 这是为了让房主直接看到当前分享出去的 zstd 端口和本地游戏端口
  - 通过 `/zstdport` 修改后，这两个值也会随之更新

- `level`：压缩强度（1-22，建议 3-9，默认：9）
  - 数字越大，压缩效果越好，但会占用更多 CPU
  - 一般设置 3-5 就足够了，平衡性能和压缩效果

- `long_distance_matching`：是否启用长距离匹配，面向高重复服务器（默认：false）
  - 压缩率更高，代价是每条连接多吃内存；默认关闭
  - `window_log` ≤ 27 时对未升级的老客户端线兼容

- `window_log`：LDM 窗口，取 2 的幂的指数（默认：0 = 保守默认 24，约 16MiB）
  - 24≈16MiB，25≈32MiB，27≈128MiB（每方向每连接）
  - 仅在 `long_distance_matching=true` 时生效；>27 需要客户端同步设置相同的 `window_log`

- `dictionary_auto`：**一键全自动字典（推荐）**。设为 true 即可，其余全自动（默认：false）
  - 服务端自动采样在线流量 → 累计约 32 次连接后自动后台训练 → 训练完自动启用并下发给玩家；无需再编辑文件、无需重启、无需手动分发
  - 玩家首次进服自动下载字典并被提示重连一次，之后即享字典压缩
  - 对登录初始的 registry/tag/recipe 爆发与海量小包提升最大
  - 若显式设了下面的 `dictionary`，则以它为准

- `dictionary`：（手动）训练字典文件名，位于 `config/zstdnet/dict/`（默认：空 = 不启用）
  - 仅在你想固定指定某本字典时才用；否则优先用 `dictionary_auto`
  - 玩家首次进服时自动下发（见「压缩调优与字典」一节）

- `dictionary_capture`：（手动制作）把每条连接开头的数据采样到 `config/zstdnet/dict/samples/` 供训练（默认：false）
  - `dictionary_auto=true` 时无需手动开；仅用于制作字典，采够样本后请关闭

- `dictionary_train`：（手动制作）一次性训练。设为 true 并保存，用 `samples/` 训练出 `dict/trained.dict`（默认：false）
  - 之后把 `dictionary` 改成 `trained.dict`、本项改回 false。`dictionary_auto=true` 时无需手动开

- `transform`：实体包流变换，面向大量实体/生物场景（默认：false）
  - 在 ZSTD 之前对「服务端→客户端」包流做**可逆去交错**：把实体移动/转头/速度的字段、以及大量相似生物的元数据等高重复内容聚到一起，显著提升机械动力契约体、刷怪塔/怪潮等高实体场景的压缩率
  - **仅在客户端也开启 `transform=true` 并 advertise 时才对该连接生效**；对未升级 / 未开启的客户端逐字节兼容、自动回退原样转发
  - 使用字典的连接会优先走字典（该连接不变换），二者不冲突
  - 正确性不依赖内置 packet 表：表缺/不符只会少压、绝不损坏数据

- `transform_max_version`：变换最高版本（默认：3，生效版本取客户端/服务端两端较小值）
  - `1`=仅版本无关的去交错；`2`=再加实体移动 SoA 拆列；`3`=再加生物包按类型分组
  - 仅在覆盖到的 MC 版本（1.19.2 / 1.20.1 / 1.21.1）启用 2/3 的实体级收益；未覆盖版本自动退化为 1

- `transform_coalesce_ms`：（预留，暂未启用）合并窗口毫秒，当前恒按 0 处理（默认：0）

- `max_conn_per_ip`：每个 IP 最多能同时连接的数量（默认：9999）
  - 设为 0 或负数表示不限制
  - 防止单个 IP 占用过多连接

- `max_req_per_window`：每个 IP 在一定时间内最多能发起的请求次数（默认：50）
  - 设为 0 或负数表示不限制
  - 防止恶意刷请求

- `request_window`：请求计数的时间范围（默认：10s）
  - 配合 max_req_per_window 使用，比如 10s 内最多 50 次请求

- `ban_duration`：超过限制后封禁的时间（默认：1m）
  - 防止恶意攻击，被封禁的 IP 暂时无法连接

- `stats_interval`：服务器日志显示流量统计的间隔（默认：0s，关闭统计日志）
  - 设置为正数间隔时，在控制台定时显示流量情况

- `flush_interval`：数据压缩后多久发送一次（默认：2ms）
  - 设为 0 表示每次压缩后立即发送
  - 数值越小，延迟越低，但可能增加网络开销

- `idle_timeout`：后端连接的空闲超时时间（默认：0）
  - 设为 0 表示不超时，保持连接一直活跃
  - 非 0 值表示如果连接空闲超过这个时间就自动断开

- `max_rate_per_conn_bps`：每个连接的最大速度限制（默认：0）
  - 单位是字节/秒，设为 0 表示不限制
  - 防止单个连接占用过多带宽

- `max_rate_global_bps`：所有连接的总速度限制（默认：0）
  - 单位是字节/秒，设为 0 表示不限制
  - 控制整体带宽使用

- `burst_bytes`：允许的突发流量大小（默认：262144）
  - 单位是字节，相当于流量的"缓冲池"
  - 即使设置了限速，短时间内的突发流量也可以超过限制

- `voice_chat_passthrough`：是否为语音模组启用原样 UDP 转发（默认：true）
  - 语音流量不会经过 zstd 压缩
  - 设为 false 后将完全关闭语音 UDP 处理（不探测、不接管、不下发）

- `voice_transport`：语音/UDP 模组的传输方式（默认：tunnel）
  - `tunnel`：语音也走 ZstdNet 的公网入口端口（与游戏同一个端口），只需对外放行入口端口一个口
  - `bridge`：客户端直连「真实服务器同端口」的语音 UDP，服务端不额外中转，需要你单独放行该 UDP 端口
  - 使用本功能时，请把 Simple Voice Chat 的 `voice_host`、Plasmo 的 `[host.public].ip` 留默认（空 / `0.0.0.0`），否则客户端会绕过 ZstdNet

- `extra_udp_ports`：额外需要透传的 UDP 端口（逗号分隔，默认：空）
  - 用于自动探测覆盖不到的其它 UDP 模组，例如 `extra_udp_ports=24454,30000`
  - 留空表示只用自动探测（Simple Voice Chat / Plasmo Voice）

- `voice_chat_listen`：语音的公网 UDP 入口（可选，旧版手动配置）
  - 单机开房 / LAN 模式下，生成出来的默认值通常是 `0.0.0.0:24455`
  - 独立语音端口模式下必须显式填写这里

- `voice_chat_target`：语音的后端 UDP 目标（可选）
  - 单机开房 / LAN 模式下，生成出来的默认值是 `127.0.0.1:25565`
  - 专用服生成出来的默认值是 `127.0.0.1:24454`
  - 独立语音端口模式下，若 `voice_chat_listen` 已填写，默认会指向 `127.0.0.1:<SVC端口>`

### 客户端配置文件 `zstdnet-client.toml` 内容

```toml
# Configuration file

[general]
	# zstd compression level for client->server stream
	level = 3
```

**配置项说明：**

- `level`：客户端到服务端流的 Zstd 压缩级别（默认：3，范围：1-22）
  - 级别越高，压缩率越好，但 CPU 使用率也会增加
  - 建议在 3-5 之间选择，平衡压缩效果和性能

- `long_distance_matching`：是否对「客户端→服务端」流启用 LDM（默认：false）
- `window_log`：LDM 窗口指数（默认：0 = 保守默认 24）；仅当目标服务器也启用时才设 >27
- `dictionary`：手动指定的字典文件，位于 `config/zstdnet/dict/`（默认：空）
  - 通常不必填：连接使用字典的服务器时会自动下载字典
- `transform`：是否对支持的服务器开启实体包流变换（默认：false）
  - 开启后客户端会在握手里 advertise 支持，并对「服务端→客户端」流装逆向解码器
  - 仅当所连服务器也开启 `transform=true` 时该连接才真正变换；否则逐字节兼容、原样透传
  - 面向大量实体/生物服务器（机械动力契约体、刷怪塔等），可显著降低这类场景的下行带宽

## 压缩调优与字典

以下选项全部 opt-in、默认关闭——默认配置与历史行为逐字节一致，并对未升级的客户端保持兼容。

### 长距离匹配（LDM）
对于「同样的大结构在几分钟内反复出现」的服务器（Create 系 / 大型整合包），在服务端设
`long_distance_matching=true`（可再加 `window_log=25`）。`window_log` ≤ 27 时无需更新客户端即可解码。
代价：每方向每连接约 `2^window_log` 字节内存，繁忙服务器会累加，故默认关闭。

### 实体包流变换（面向大量实体/生物）
当服务器有**大量实体**（机械动力契约体匀速运动、刷怪塔/怪潮里成片相似生物）时，实体移动/转头/速度包
和生物元数据里的高重复字段被「结构字节」**交错**打散，ZSTD 难以跨包匹配，压缩收益偏低。

开启变换后，代理在喂给 ZSTD **之前**，对「服务端→客户端」包流做一层**可逆去交错**：把同字段的值聚成连续列
（如所有实体的 Δx 一列、entityId 一列、相似生物的元数据相邻摆放），解压后再**逐字节精确还原**。这样跨 tick 的
同字段重复就变成 ZSTD 能直接命中的长匹配，借助连续帧（匹配历史跨 flush 保留）显著提升压缩率。

用法：**服务端与客户端都设 `transform=true`** 即可（默认关闭）。要点：
- 默认关闭时与历史行为逐字节一致；一端未升级 / 未开启则该连接自动回退原样转发，连接与玩法不受影响。
- 正确性不依赖内置 packet 表：表只用于编码端决定怎么拆，表缺/不符只会少压、绝不损坏数据（fail-closed）。
- 使用字典的连接优先走字典（该连接不变换），二者不冲突。
- 实体级收益（version 2/3）覆盖 1.19.2 / 1.20.1 / 1.21.1；其他版本自动退化为版本无关的去交错（version 1）。

### 字典（零配置自动分发）
训练字典对登录初始爆发（registry/tag/recipe）和大量相似小包（实体移动、方块更新）提升最大。

**推荐——一个开关、全自动：** 在服务端设 `dictionary_auto=true`，配置到此结束。服务器随后自行：
1. 后台采样每条连接开头的流量（每条连接会被切成多个小样本块——字典训练对样本「个数」有最低要求）；
2. 仅需两三名玩家正常进服一次的样本量，即在后台线程训练出 `config/zstdnet/dict/trained.dict`；
3. 把字典**热插启用（不断开任何在线连接）**，并下发给**所有在线玩家 + 新进玩家**——**无需第二次编辑、无需重启、无需手动分发字典。**

拿到字典的玩家会被提示重连一次（清楚的游戏内提示），之后即享字典压缩；已有字典的玩家不会被踢。整个过程服务器照常运行（只是在短暂的「学习」阶段还没用字典压缩）。训练完成后字典永久生效。

**不需要**七八个人反复进出服务器——正常第一场游戏里大家陆续登录就足以达到训练门槛。

<details><summary>手动流程（进阶——仅当你想自己掌控训练时机）</summary>

1. 设 `dictionary_capture=true`，让玩家连接一段时间（样本累积到 `config/zstdnet/dict/samples/`），再设回 false。
2. 设 `dictionary_train=true` 并保存，生成 `config/zstdnet/dict/trained.dict`（也可用 `zstd --train` 命令行离线训练）。
3. 设 `dictionary=trained.dict`、`dictionary_train=false`。

</details>

两种方式玩家都无需任何配置：进服后服务端会公告自己的字典；没有该字典的客户端会下载它（≤1 MB），缓存到
`config/zstdnet/dict/auto/` 并记录到该服务器，然后被主动断开并提示重连。从下一次连接起，字典从第一帧就生效；
之后再进服时已持有字典，直接以字典压缩连接、不再被踢。

注意：若之后更换了服务器字典，缓存了旧字典的玩家可能需要删除一次 `config/zstdnet/dict/auto/` 才能恢复。

## 依赖

- zstd-jni

## 相对原版的新增内容

本仓库由 **xuenai · 麦块联机** 在原项目 [wish131400/zstdnet](https://github.com/wish131400/zstdnet) 基础上深度二次开发。
原版已具备 ZSTD 压缩代理、自动接管（`auto_takeover`）、同端口 UDP 透传、HUD 与多加载器
（Forge / Fabric / NeoForge）等基础能力，但仅覆盖 1.20.1 / 1.21.1。在此之上，本仓库新增与扩展了：

- **大幅扩展版本与平台**
  - 新增 **Forge 1.18.2**、**Forge 1.19.2**、以及 **Minecraft 26.1（26.1.1 / 26.1.2，NeoForge + Fabric，非混淆新时代）**
  - 新增**插件端 / 混合端变体**（Bukkit / Spigot / Paper / Purpur、Arclight / Mohist 等）：一个 jar 跨 1.20.1 与 1.21.x，并**支持 Folia**
- **压缩能力增强（全部 opt-in、默认关闭、对未升级客户端线兼容）**
  - **长距离匹配（LDM）**：面向高重复大结构服务器进一步提压
  - **训练字典 + 零配置自动分发**：`dictionary_auto=true` 一键，自动采样 → 后台训练 → 热插启用 → 下发玩家，无需重启或手动分发
- **实体包流变换（`transform`）**：面向大量实体 / 生物场景（机械动力契约体、刷怪塔、怪潮），在 ZSTD 之前对下行包流做可逆去交错，显著提升这类场景压缩率；协商生效、默认关闭、`fail-closed` 不损坏数据
- **语音模组零配置兼容**：在原版同端口 UDP 透传之上，新增**自动探测后端 Simple Voice Chat / Plasmo Voice 的独立 UDP 端口**并接管，玩家进服自动开监听、服主免手填；支持 `tunnel`（语音复用入口单端口）/ `bridge` 两种传输（mod 服与插件端均适用）
- **架构与合规**
  - 核心收敛为**单一真源**（`mods/common` + Platform SPI），改共享逻辑只改一处即可传播到所有变体
  - 补充完整的二次开发署名、致谢与 `LICENSE` / `NOTICE`（随每个 jar 分发）
  - 新增中英文新手图文指南与语音模组使用指南（见 [`docs/`](docs/README.md)）

## 致谢与来源

本项目是基于原作者 **wish** 的开源项目 [wish131400/zstdnet](https://github.com/wish131400/zstdnet)（MIT License）
二次开发而来，由 **xuenai · 麦块联机（[minekuai.com](https://minekuai.com)）** 维护与扩展。
原作者保留其著作权；本仓库新增 / 修改部分的著作权归本项目维护者所有。整体仍以 MIT License 发布。

内置第三方组件：[zstd-jni](https://github.com/luben/zstd-jni)（Luben Karavelov，BSD 2-Clause），
其中内含 [Zstandard](https://github.com/facebook/zstd)（Meta，BSD 2-Clause）。
完整声明见随发行包一同分发、并打包进每个 jar 内的 `LICENSE` 与 `NOTICE`。

## License

本项目采用 **MIT License**。

- Copyright (c) 2026 wish（原作者 — https://github.com/wish131400/zstdnet）
- Copyright (c) 2026 xuenai · 麦块联机 / MineKuai（https://minekuai.com）— 二次开发与新增部分
