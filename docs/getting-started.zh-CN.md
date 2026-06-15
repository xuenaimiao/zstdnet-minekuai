# ZstdNet 新手使用指南

ZstdNet 压缩 Minecraft 联机流量、省带宽。**服务器和玩家两边都装才生效。**

支持：Forge 1.19.2 / 1.20.1、NeoForge 1.20.1 / 1.21.1、Fabric 1.20.1 / 1.21.1；插件版一个 jar 通用。

## 玩家（进别人的服）

1. 把 **ZstdNet 客户端 mod** 丢进 `mods/`（加载器、版本和服务器一致）。
2. 进服：mod 服地址照常填；插件服用服主给的端口（一般是 `IP:游戏端口+1`，如 `IP:25566`）。
3. 进游戏输入 `/zstdhud on` 看压缩状态。装了语音 mod 会自动通，见 [语音指南](voice.zh-CN.md)。

## 服主：mod 服（Forge / NeoForge / Fabric）

1. 把 **ZstdNet** 丢进 `mods/`。
2. `server.properties` 设 `online-mode=false`，启动。默认自动接管端口，玩家照常连原端口。
3. 防火墙放行 `server-port`（**TCP+UDP**）。

## 服主：插件服（Paper / Spigot / Purpur / 混合端）

1. 把 `zstdnet-bukkit-<版本>.jar` 丢进 `plugins/`，启动。
2. 默认生成 `plugins/ZstdNet/zstdnet-server.properties`，入口端口 = 游戏端口 + 1（如 `25566`）。
3. 防火墙放行该入口端口（**TCP+UDP**）。装 mod 的玩家连 `IP:25566`，没装的连原 `25565`。
4. 管理指令（需 OP）：`/zstdnet status | reload | start | stop`。

## 更多

完整参数（压缩等级、FRP、真实 IP、字典等）见 [完整文档](../README.zh-CN.md)。English: [getting-started.en-US.md](getting-started.en-US.md)。
