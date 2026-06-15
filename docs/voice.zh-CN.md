# 语音 mod 使用指南

装了 ZstdNet 后，**Simple Voice Chat、Plasmo Voice 等语音 mod 零配置自动可用**（mod 服、插件服都一样）。Sable、机械动力·航空学这类同端口语音本来就通。

## 玩家

装**和服务器一样的语音 mod**，正常进服，语音自动连通，**不用在语音 mod 里改任何地址 / 端口**。

## 服主

默认就是零配置，唯一要做的是**放行入口端口，TCP 和 UDP 都放**（常见坑：只放了 TCP）。

- mod 服：放行 `server.properties` 的 `server-port`。
- 插件服：放行 `zstdnet-server.properties` 里的 `listen` 端口；插件会自动扫描 `plugins/voicechat/`、`plugins/PlasmoVoice/`。

`voice_transport`（在 `zstdnet-server.properties`，默认 `tunnel`）：

- `tunnel`：语音和游戏共用入口端口，**只放行一个口**（推荐，适合 FRP / 内网穿透）。
- `bridge`：语音单独直连真实端口，需**额外放行该语音端口（UDP）**。

冷门 UDP mod 探测不到时，加一行 `extra_udp_ports=端口` 再 `/zstdnet reload`。

## 听不到 / 说不了，怎么查

1. 客户端和服务器是同一个语音 mod、版本对应吗？
2. 入口端口的 **UDP 放行了吗**？
3. 插件服：先装插件后装语音的，`/zstdnet reload` 一次或重启。

## 已知边界

管理员把 SVC 的 `voice_host` 或 Plasmo 的 `[host.public].ip` 填成具体公网地址时，客户端会绕过 ZstdNet。想让 ZstdNet 接管语音，请**留默认（空 / `0.0.0.0`）**。
