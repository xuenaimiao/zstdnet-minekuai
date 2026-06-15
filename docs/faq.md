# 常见问题与排错

按你遇到的现象往下找。

---

## 进不去服务器 / 连接失败

逐项排查：

1. **版本 / 加载器对不对**：客户端 jar 必须和你的游戏版本、加载器一致（见 [玩家怎么用](for-players.md) 第一步）。
2. **服务端开了吗**：服主的 `zstdnet-server.properties` 里 `enabled=true` 了吗？
3. **后端 `online-mode`**：mod 服后端需要 `online-mode=false`（见 [开服主怎么用](for-server-owners.md)）。
4. **连的端口对不对**：
   - mod 服默认 `auto_takeover=true` → 连**原来的地址端口**即可。
   - 插件服 / 手动模式 → 要连服主指定的 **zstd 入口端口**（插件服默认 `:25566`）。
5. **防火墙 / 安全组**：服主要放行对外的 zstd 入口端口。
6. **开了 `trust_proxy_protocol=true`？** 那么直连 zstd 端口、但不带 PROXY v2 头的连接会被**故意拒绝**
   （防伪造真实 IP）。直连场景请保持 `trust_proxy_protocol=false`。

---

## Ratio 接近 100%，是不是没生效？

`Ratio` 越低越省；接近 100% 说明这条连接几乎没被压缩，常见原因：

- 你连的服务器**没装** ZstdNet 服务端 / 插件（只有你一边装没用）。
- 你**没连到 zstd 入口端口**（插件服要连 `:25566` 那种独立端口，别连成后端原端口了）。
- 这段流量本身重复度低（少见）。

先确认服主侧确实装好、且你连的是 zstd 入口。

---

## 启动游戏就崩溃 / 闪退

几乎都是 **jar 放错**：

- Forge 的 jar 放进了 Fabric（或反过来）；
- 版本号不对（比如 1.20.1 的 jar 放进 1.21.1）；
- 放进了**别的版本**的 mods 文件夹（整合包通常版本隔离，注意是不是放对了那个版本）。

回到 [玩家怎么用](for-players.md) 第一、二步核对。

---

## 找不到 `/zstdhud` 或 `/zstdport` 指令

- 说明 mod 没真正加载：确认 jar 在**你正在玩的那个版本**的 `mods` 文件夹里。
- `/zstdport` 是**客户端**指令，用于单机/局域网开房场景。

---

## 会和其他联机 mod 冲突吗？

一般不会。ZstdNet 工作在网络转发层，对玩法透明。如果你用了**完全替换开房界面**的高级联机 mod，
导致看不到 Zstd 端口输入框，用 `/zstdport show` 查看、`/zstdport zstd <端口>` 手动指定即可。

---

## 语音模组（Simple Voice Chat 等）能用吗？

能，**零配置自动兼容**：Simple Voice Chat、Plasmo，以及 Sable / 机械动力：航空学这类「同端口 UDP」模组都支持。

注意点：

- 如果你的服走 FRP / 隧道，请确保前置转发层**同时转发同一个端口的 TCP 和 UDP**；只转 TCP 的话语音会回退到较慢的 TCP 通道。
- 需要手动调语音端口时：`/zstdport voice <端口>`（后端语音端口）、`/zstdport zstdvoice <端口>`（公网语音入口）。

---

## 开了 transform 但好像没更省？

- 确认**服务端和客户端两边都设了** `transform=true`（少一边就自动回退、不生效）。
- 它主要在**实体/生物密集**时才有明显收益；空旷场景看不出差别是正常的。到契约体旁 / 刷怪塔再看 `/zstdhud` 的 Ratio。
- 详见 [省流量进阶设置 → 实体变换 transform](save-bandwidth.md#2-实体变换-transform实体生物特别多的服)。

---

## 插件服后端看到的玩家 IP 都是 127.0.0.1？

插件版默认情况下，经代理进来的玩家在后端看是本机回环 `127.0.0.1`。绝大多数服无影响；
若你用 FRP/反代且需要后端按真实 IP 封禁 / 风控，目前请用平台原生的 IP 转发
（Velocity 现代转发 / BungeeCord / 服务端 `proxy-protocol`）。这是已知限制，后续增强中。

---

## 会让游戏更卡吗？

ZstdNet 自身的 CPU 开销很小。如果你本来就在大型整合包里卡，那通常是显卡 / 服务器核数等瓶颈，
和本模组关系不大。CPU 真的吃紧时，可把 `level` 调低一点（见 [省流量进阶](save-bandwidth.md)）。

---

## 还是没解决？

仓库根目录的 [`README.zh-CN.md`](../README.zh-CN.md) / [`README.en-US.md`](../README.en-US.md)
有更完整的参数说明和 FRP / 真实 IP 等高级场景配置，可作为进一步参考。
