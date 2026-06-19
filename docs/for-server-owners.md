# 开服主怎么用

目标：给你的服务器装上 ZstdNet，让装了客户端的玩家自动省流量。

> **重要前提**：压缩需要两端配合。服务端只是「一半」，**想省流量的玩家还得各自装客户端 mod**
> （见 [玩家怎么用](for-players.md)）。没装的玩家照常直连、不受影响，只是没有压缩。

---

## 先选对路：你的服务器是哪种？

| 你的服务器 | 看下面哪节 |
|---|---|
| 用 **Forge / NeoForge / Fabric** 跑 mod 的服（有 `mods` 文件夹） | 👉 [A. mod 服](#a-mod-服forge--neoforge--fabric) |
| 用 **Paper / Spigot / Purpur** 跑插件的服（有 `plugins` 文件夹，没有 mod 加载器） | 👉 [B. 插件服](#b-插件服paper--spigot--purpur) |
| **混合端**（Arclight / Mohist / CatServer，既能 mod 又能插件） | 👉 [B. 插件服](#b-插件服paper--spigot--purpur)（推荐用插件版，最省心） |
| 我只是**单机开房**给朋友玩 | 👉 [C. 单机 / 局域网开房](#c-单机--局域网开房) |

---

## A. mod 服（Forge / NeoForge / Fabric）

### 1. 后端先准备好

打开服务器的 `server.properties`，确认：

```properties
online-mode=false
```

- `online-mode=false`：关闭后端的正版验证（代理转发场景需要）。
- `network-compression-threshold`：**不用管**，ZstdNet 启动时会自动接管这一项。

> **想要内置正版验证（全部模组加载器已支持：Fabric + Forge + NeoForge，1.18.2 ~ 26.1）**：直接把 `online-mode=true` 即可——ZstdNet 默认 `premium_verification=auto` 会自动开启验证并在运行时把后端切到离线以保住压缩（不改你磁盘上的 `online-mode`），正版玩家保留真实 UUID/皮肤，无需再装 TrueUUID。详见仓库 `PREMIUM_VERIFICATION.md`。
>
> 若仍想纯离线运行，把 `online-mode=false` 即可（或设 `premium_verification=off`）。**Bukkit/Spigot 插件端**暂不支持内置验证（独立代理无法挂钩 MC 登录），如需保留正版身份可在后端额外搭配 [TrueUUID](https://www.curseforge.com/minecraft/mc-mods/trueuuid) 等。

### 2. 放入服务端 jar

把和你**服务端版本 + 加载器**对应的 ZstdNet jar 放进服务器的 `mods` 文件夹，重启服务器。

### 3. 用默认配置（推荐）

第一次启动后会生成 `config/zstdnet-server.properties`，默认就是最省心的样子：

```properties
enabled=true
auto_takeover=true
```

`auto_takeover=true` 的意思是：

- ZstdNet 自动读取 `server.properties` 里的 `server-port`，**把它接管成公网入口**；
- 把后端 Minecraft 自动挪到另一个本地空闲端口；
- **玩家继续连原来的地址和端口**，什么都不用改。

完成！装了客户端的玩家连进来就自动压缩了。

> 如果你前面有 FRP / 反代 / 内网穿透：确保公网入口最终转发到 ZstdNet 接管的那个端口（也就是原来的游戏端口），
> 不要绕过它直连后端，否则就没压缩了。

### 4.（少数情况）手动模式

如果你不想让它自动接管端口，可以关掉自动、手填：

```properties
auto_takeover=false
listen=0.0.0.0:35565     # 玩家来连的公网入口（zstd 入口）
target=127.0.0.1:25565   # 你后端 Minecraft 的真实端口
```

此时要**告诉玩家连 `listen` 那个端口**（这里是 `35565`）。

---

## B. 插件服（Paper / Spigot / Purpur）

插件加载时游戏端口已经被占了，插件没法像 mod 那样接管原端口，所以插件版用**独立端口**：

```text
没装 mod 的玩家   ─────────────►  MC 服务器 25565        （插件不碰这条，照常玩）
装了 ZstdNet 的玩家 ─► 插件代理 25566 ─(解压)─► 127.0.0.1:25565 后端
```

### 步骤

1. 把 `zstdnet-bukkit-<版本>.jar` 放进服务器的 `plugins/` 文件夹，启动。
   > 同一个 jar **同时支持 1.20.1 和最新 1.21.x**，不用区分。
2. 首次启动生成 `plugins/ZstdNet/zstdnet-server.properties`，默认：
   ```properties
   enabled=true
   auto_takeover=false
   listen=0.0.0.0:25566      # zstd 对外入口（默认 = 游戏端口 + 1）
   target=127.0.0.1:25565    # 本机 Minecraft 后端端口
   ```
3. 在**防火墙 / 云服务器安全组**放行 `listen` 端口（这里是 `25566`）。原版玩家继续用 `25565`。
4. **告诉装了 ZstdNet 客户端的玩家**：在服务器列表里把地址填成 `你的IP:25566` 就能享受压缩。

> 提示：`listen` 端口要和游戏端口不同、且没被占用；需要的话改这一行即可。插件端请保持 `auto_takeover=false`。

### 管理指令（需要 `zstdnet.admin` 权限，默认 OP 可用）

```text
/zstdnet status   查看代理状态、端口、连接数、压缩比
/zstdnet reload   重新读配置并重启代理（改完配置一般几秒内也会自动热重载）
/zstdnet start    启动代理
/zstdnet stop     停止代理
```

### 混合端（Arclight / Mohist / CatServer）

底层是 Forge/NeoForge、又能放插件：**最省心是直接用本插件**（丢进 `plugins/`），
不依赖混合端对 coremod / 自动接管的兼容性。

---

## C. 单机 / 局域网开房

你在自己电脑玩单人世界、想「对局域网开放」让朋友进：

1. 客户端装好 ZstdNet（见 [玩家怎么用](for-players.md)）。
2. 正常点「对局域网开放」。ZstdNet 会在开放界面里多出一个 **Zstd 端口**输入框。
   - 游戏端口一般留空即可（自动跟随）。
   - Zstd 端口可留默认；被占用会自动换一个可用的。
3. 开放成功后，聊天框会提示**本次实际用的 Zstd 端口**（可点击复制）。
4. 把 `你的公网IP或域名:那个Zstd端口` 发给朋友连接。

随时可用指令查看 / 改端口：

```text
/zstdport show              查看当前端口
/zstdport zstd 35565        指定公网 zstd 端口
/zstdport game 25565        指定游戏端口（一般不用）
```

---

## 想压得更狠？

默认已经在压了。想进一步降流量（字典、面向大量实体/生物的 **transform** 等），见
👉 [省流量进阶设置](save-bandwidth.md)。

## 想让后端看到玩家真实 IP（FRP / 反代）？

简单说：直连 zstd 入口时本就能拿到真实 IP；前面有 FRP/反代时需要额外配置 `trust_proxy_protocol`。
细节见仓库根目录 [`README.zh-CN.md`](../README.zh-CN.md) 的「真实 IP / FRP PROXY v2 怎么选」一节。
