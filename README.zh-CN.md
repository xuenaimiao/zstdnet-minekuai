# ZstdNet

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
  Forge 1.20.1、NeoForge 1.20.1、NeoForge 1.21.1、Fabric 1.20.1、Fabric 1.21.1
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

如果你仍然希望保留正版校验能力，可以额外搭配 [TrueUUID（正版离线共存）](https://www.curseforge.com/minecraft/mc-mods/trueuuid)。

- 这个模组适合“后端保持 `online-mode=false`，但登录阶段仍然执行正版校验”的场景
- 可以在离线模式下尽量保留正版 UUID、名称大小写与皮肤属性等信息
- 对于需要离线转发、内网穿透、代理链路，同时又不想完全放弃正版验证的服务器来说会比较实用

也就是说，ZstdNet 负责接管压缩与转发；如果你还需要正版验证，可以再配合 TrueUUID 这类模组一起使用。

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

默认推荐直接使用 `auto_takeover=true`。这样模组会在启动时读取 `server.properties` 里的 `server-port`，把它作为公网入口 `listen`，再自动把后端 Minecraft 挪到另一个本地空闲端口 `target`，服主通常不需要再手动填写端口。

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

可以，而且是**零配置**的。语音流量不压缩，只做原样转发。ZstdNet 会自动探测后端常见语音模组（Simple Voice Chat、Plasmo Voice）监听的独立 UDP 端口，并把它们一起接管，玩家进服后自动在客户端本机为这些端口开监听——无需你手动填任何端口。

语音有两种传输方式，由服务端配置 `voice_transport` 控制：

- **`tunnel`（默认）**：语音 UDP 也走 ZstdNet 的公网入口端口（和游戏同一个端口），服务端按通道拆分后转给后端各语音端口。**这样你只需要对外放行入口端口一个口**，FRP / 内网穿透也只穿一个口即可。
- **`bridge`**：客户端在本机把语音 UDP 直连到「真实服务器的同一个端口」，服务端不额外中转。这种方式需要你**为该语音端口单独做公网放行 / 端口映射**。

其它说明：

- **同端口语音模组**（Sable / 机械动力：航空学，以及把 SVC 设成跟随服务器端口的情况）一直都能用——它们的 UDP 跟游戏走同一个端口，由内置 game 直通覆盖。
- **已知边界**：如果管理员把 Simple Voice Chat 的 `voice_host` 或 Plasmo 的 `[host.public].ip` 显式填成了某个公网地址，客户端会按你填的地址直连、**绕过 ZstdNet**（服务端会打印一条 WARN 提示）。要让 ZstdNet 接管语音，请把它们留默认（空 / `0.0.0.0`）。
- **其它 UDP 模组**：自动探测覆盖不到的，可以用 `extra_udp_ports` 手动补一份端口清单。
- 如果语音路由没起来，游戏本身的 TCP 连接仍然正常，只是语音会离线。
- 旧版 `voice_chat_listen` / `voice_chat_target` 仍然保留兼容；`/zstdport voice <端口>`、`/zstdport zstdvoice <端口>` 仍可手动调整。

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

## 压缩调优与字典

以下选项全部 opt-in、默认关闭——默认配置与历史行为逐字节一致，并对未升级的客户端保持兼容。

### 长距离匹配（LDM）
对于「同样的大结构在几分钟内反复出现」的服务器（Create 系 / 大型整合包），在服务端设
`long_distance_matching=true`（可再加 `window_log=25`）。`window_log` ≤ 27 时无需更新客户端即可解码。
代价：每方向每连接约 `2^window_log` 字节内存，繁忙服务器会累加，故默认关闭。

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

## License

本项目采用 MIT License。
