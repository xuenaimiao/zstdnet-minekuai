# ZstdNet

> 🚀 **First time / never installed a mod or run a server?** Start with the **[beginner-friendly Getting Started guide](docs/getting-started.en-US.md)** — plain language, step by step. All docs live in [`docs/`](docs/README.md).

ZstdNet is a Minecraft Java Edition mod that uses ZSTD to compress relayed traffic between clients and servers, with the goal of significantly reducing public bandwidth usage in high-repetition data scenarios.

It is especially suitable for:

- Create-based servers
- Large modpack servers
- Multiplayer setups using FRP / NAT traversal / tunnel forwarding
- Singleplayer hosts who want to provide a more bandwidth-efficient external entry point for friends

## What This Mod Does

- When a client enters a ZstdNet address, the mod automatically starts a temporary local proxy and takes over the connection
- Provides a dedicated Zstd entry point on the server side and forwards compressed traffic to the backend Minecraft port
- Supports both dedicated servers and LAN worlds hosted from singleplayer
- Includes a HUD so you can check whether the connection is using zstd and view live traffic stats in-game
- Supports vanilla status ping passthrough so the server list can still query the server normally
- The client-side local proxy also provides raw UDP passthrough on the same local port, for compatibility with mods such as Sable / Create Aeronautics that depend on Minecraft same-port UDP

## Real-World Results

The main purpose of this mod is to reduce public bandwidth usage caused by highly repetitive packet traffic.

In large modpack [齿轮盛宴官方网站](https://www.xn--dctt54dhmrbwo.com) environments, the compression gains can be very significant. Here is a real example of server-side stats:

```text
Raw: 189.06 GB (3.6MB/s) | Zstd: 10.28 GB (252.7KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (5.0MB/s) | Zstd: 10.28 GB (234.3KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (3.2MB/s) | Zstd: 10.28 GB (215.5KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.07 GB (4.8MB/s) | Zstd: 10.28 GB (303.7KB/s) | Ratio: 5.44% | Conns: 8
```

**The lower the ratio, the less traffic is actually being transmitted after compression.**

## Installation

It is recommended to install this mod on both the client and the server.

- Current versions supported in this repository:
  Forge 1.19.2, Forge 1.20.1, NeoForge 1.20.1, NeoForge 1.21.1, Fabric 1.20.1, Fabric 1.21.1
- **Plugin servers (no mod loader)**: Bukkit / Spigot / Paper / Purpur, plus hybrid servers like Arclight / Mohist — see "[Plugin & Hybrid Servers](#plugin--hybrid-servers-bukkit--spigot--paper--arclight--mohist)" below
- When connecting to a remote ZstdNet-enabled server: the client needs it
- When using the built-in Zstd server entry: the server needs it
- When opening a LAN world and sharing a Zstd entry externally: the host client needs it

## How Players Connect

If the server owner uses the default recommended `auto_takeover=true` setup, players usually keep using the same public address and port they already know. They do not need to learn a second port just for ZstdNet.

For example:

```text
play.example.com:25565
1.2.3.4:25565
```

In the default auto-takeover mode:

- Players keep connecting to the public port from `server.properties`
- ZstdNet automatically takes that port over
- The backend Minecraft server is moved to another local port automatically

Only when the server owner disables `auto_takeover` and switches to manual mode do players need to use a separate `listen` port, for example:

```properties
auto_takeover=false
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

## Required Preparation

Before using the built-in ZstdNet server entry on a dedicated server, make sure the backend Minecraft server is configured correctly.

In your server's `server.properties`, at minimum set:

```properties
online-mode=false
```

- `online-mode=false`: disable vanilla online authentication on the backend server
- `network-compression-threshold=1048576`: when the built-in ZstdNet server entry is enabled on a dedicated server, the mod will take this over automatically at startup, so you usually do not need to fill it manually

If you keep vanilla authentication enabled on the backend server, connections may fail. If vanilla network compression is not taken over by ZstdNet, compression efficiency may also be much worse than expected.

If you still want premium account verification while keeping the backend in offline mode, you can additionally use [TrueUUID](https://www.curseforge.com/minecraft/mc-mods/trueuuid).

- This is useful for setups where the backend stays on `online-mode=false`, but you still want login-time premium account verification
- It can help preserve premium UUIDs, correct name casing, and skin-related profile data while running in offline mode
- This is especially practical for servers that need offline forwarding, NAT traversal, or proxy-style chains without fully giving up premium account checks

In short, ZstdNet handles compression and forwarding; if you also need premium account verification, you can pair it with a mod such as TrueUUID.

## Dedicated Server Setup

On first launch, the mod generates:

```text
config/zstdnet-server.properties
```

The most common configuration looks like this:

```properties
enabled=true
auto_takeover=true
```

Meaning:

- `auto_takeover=true`: read `server.properties` at startup and automatically use `server-port` as the public entry
- `listen` / `target`: resolved internally by ZstdNet in auto mode, so they usually do not need to be filled manually

In other words:

- Players still connect to the original public port
- ZstdNet takes that port over automatically
- The backend server is moved to another local port automatically
- ZstdNet decompresses/compresses traffic and forwards it internally

If you also use FRP, HAProxy, NAT traversal, or any other forwarding layer, make sure your public entry ultimately forwards to `listen`. Do not forward directly to the vanilla game port, or zstd will be bypassed.

If your modpack includes Sable / Create Aeronautics or any other mod that depends on same-port UDP, make sure the forwarding layer also forwards both TCP and UDP on the same port. ZstdNet listens on both TCP and UDP at the client-side local address `127.0.0.1:<local proxy port>` and forwards UDP packets unchanged to the same remote port; if your public tunnel only forwards TCP, Sable may fall back to its slower TCP pipeline.

With the default config, dedicated servers no longer need manual port planning. ZstdNet will read `server-port`, keep it as the public entry, and automatically move the backend to another free local port.

## Plugin & Hybrid Servers (Bukkit / Spigot / Paper / Arclight / Mohist)

If your server is a **plugin server** (Paper / Spigot / Purpur, no mod loader) or a **hybrid server** (Arclight / Mohist / CatServer, which run both Forge mods and Bukkit plugins), use the **plugin build** `zstdnet-bukkit-<version>.jar`.

**Important prerequisite first**: ZSTD compression is inherently two-sided. The plugin is only the *server half* — **players who want compression still install the existing ZstdNet client mod** (Forge/NeoForge/Fabric, unchanged). Vanilla players without the mod connect normally and are unaffected; they simply get no ZSTD compression.

**Key difference from a mod server**: by the time plugins load, the Minecraft server port is already bound, so the plugin **cannot** relocate the backend and take over the original port the way the mod does. The plugin therefore uses a **separate listen port**:

```text
vanilla / no-mod players ──────────────►  MC server 25565          (plugin doesn't touch this)
players with ZstdNet      ──► plugin proxy 25566 ──(decompress)──► 127.0.0.1:25565 backend
```

**Installation**:

1. Drop `zstdnet-bukkit-<version>.jar` into the server's `plugins/` folder and start. **The same jar supports both 1.20.1 and the latest 1.21.x.**
2. First start generates `plugins/ZstdNet/zstdnet-server.properties` with defaults:
   ```properties
   enabled=true
   auto_takeover=false
   listen=0.0.0.0:25566      # zstd proxy public entry (= server-port + 1)
   target=127.0.0.1:25565    # local Minecraft backend port
   ```
3. Open the `listen` port (e.g. 25566) in your firewall / security group. Vanilla players keep using 25565.
4. Players with the ZstdNet client mod point their server-list entry at `serverIp:25566` to get compression. All other settings (compression level, dictionaries, rate limits, …) are identical to a mod server (see "Configuration Files").

> Note: the `listen` port must differ from the Minecraft `server-port` and be free; edit that line if needed. Keep `auto_takeover=false` on plugin servers.

**Admin commands** (require permission `zstdnet.admin`, default OP):

```text
/zstdnet status   show proxy state, listen/backend ports, connections, compression ratio
/zstdnet reload   re-read config and restart the proxy (config edits also hot-reload within a few seconds)
/zstdnet start    start the proxy
/zstdnet stop     stop the proxy
```

**Voice mods are zero-config too**: the plugin auto-supports Simple Voice Chat / Plasmo Voice. It scans `plugins/voicechat/` and `plugins/PlasmoVoice/` for the voice mod's independent UDP port and, when a player joins, pushes that port to the ZstdNet client mod (over a Bukkit plugin message — harmless to vanilla players), which then opens a matching local listener automatically. The default `tunnel` mode carries voice over the single `listen` entry port (no separate voice port to open); set `voice_transport=bridge` in the config to switch. Use `extra_udp_ports` to add anything detection misses. Full details and caveats are in "[What about Simple Voice Chat, Plasmo Voice or other UDP-based voice mods](#what-about-simple-voice-chat-plasmo-voice-or-other-udp-based-voice-mods)" below.

**Hybrid servers**: Arclight / Mohist / CatServer run Forge/NeoForge underneath and accept **both mods and plugins**:

- Simplest: use this plugin (drop into `plugins/`) — it does not rely on the hybrid's coremod / auto-takeover compatibility.
- You may also use the matching Forge/NeoForge **mod build** (drop into `mods/`); but hybrids may alter port init or the network stack, so if `auto_takeover` fails to relocate the port, set `auto_takeover=false` in the mod's config and fill in `listen`/`target` manually, or just use the plugin build instead.

**About real IP (frp / reverse-proxy setups)**: on plugin servers, by default a player relayed through the proxy appears to the backend as `127.0.0.1` (loopback). This is fine for most servers. If you run behind frp/reverse-proxy and need real IPs at the Minecraft layer (per-IP bans, anti-VPN, geolocation), use the platform's native IP forwarding for now (Velocity modern forwarding / BungeeCord / server `proxy-protocol`). Transparent real-IP restoration via ZstdNet on plugin servers requires per-version netty injection that affects the whole server's networking and needs validation on real servers, so it is a planned enhancement and is not shipped in this build.

## Typical FRP Chain

Recommended chain:

```text
Player client -> Public FRP port -> Host ZstdNet listen port -> Local game port
```

For example:

- Public port: `25565`
- Backend game port: `25566` (auto-assigned if free)
- FRP public port: `25565`

Then the recommended setup is:

- Leave `auto_takeover=true` in `zstdnet-server.properties`
- Let ZstdNet keep `listen` on the configured public port
- Let ZstdNet move `target` to another local port automatically
- Configure `frpc.toml` to forward the public port to the host's public `listen`
- Players connect using the same public port they already know

## Real IP / FRP PROXY v2 Mode

If players connect directly to the ZstdNet entry port, such as public direct connection, LAN direct connection, or virtual LAN direct connection, keep:

```properties
trust_proxy_protocol=false
```

In this mode, ZstdNet reads the player's IP directly from the TCP connection. Public direct connections expose the player's public IP; LAN or virtual LAN direct connections expose the player's LAN / virtual-network IP.

Only enable this when there is an FRP / reverse-proxy layer in front of ZstdNet and you want the backend to see the player's real IP:

```properties
trust_proxy_protocol=true
trusted_proxy_ips=127.0.0.1,::1,0:0:0:0:0:0:0:1
```

The FRP TCP proxy must also send PROXY Protocol v2, for example:

```toml
transport.proxyProtocolVersion = "v2"
```

`trusted_proxy_ips` is the list of proxy machines that are allowed to tell ZstdNet the player's real IP. It is not a player IP allowlist and not a list of allowed network ranges. If frpc runs on the same machine as the server, keep the default localhost values. If frpc runs on another machine, put the LAN / virtual-network IP that machine uses to connect to this server.

Note: when `trust_proxy_protocol=true`, direct connections to the ZstdNet entry port without a PROXY v2 header will be rejected. This includes local, LAN, virtual LAN, and public direct connections to the `listen` port. This is intentional to prevent clients from spoofing real IP addresses.

## Singleplayer / LAN Hosting

When opening a singleplayer world to LAN, ZstdNet adds a Zstd port field to the "Open to LAN" screen and keeps the normal game port field compatible with vanilla or advanced LAN screens.

Recommended usage:

- Leave the game port empty unless you explicitly need a fixed Minecraft LAN port. ZstdNet will follow the actual LAN port used by this session.
- The Zstd port uses the configured port first. If that port is already occupied or reserved by the current LAN session, ZstdNet automatically falls back to another available port.
- After the LAN world is opened, the in-game chat prints the actual Zstd port. The Zstd port in that message can be clicked to copy it.

The active settings are stored in:

```text
config/zstdnet-server.properties
```

and support hot reload.

If friends are joining from outside your local network, give them this address:

```text
Your public IP or domain:Zstd port
```

For example:

```text
mc.example.com:35565
203.0.113.10:35565
```

If a LAN UI mod fully replaces the screen and the Zstd field is not visible, use `/zstdport show` to view the current ports, or `/zstdport zstd <port>` only when you need to pin a fixed public/tunnel port.

## Commands

### `/zstdhud`

Used to check or toggle the HUD:

```text
/zstdhud
/zstdhud on
/zstdhud off
/zstdhud toggle
```

### `/zstdport`

Used to view or change ports in singleplayer / LAN hosting scenarios:

```text
/zstdport show
/zstdport game 25565
/zstdport zstd 35565
/zstdport voice 25565
/zstdport zstdvoice 24455
```

Notes:

- `/zstdport` is a client-side command
- `show` can view the current config
- `voice` changes `voice_chat_target`, which is the backend voice port
- `zstdvoice` changes `voice_chat_listen`, which is the public voice entry
- In singleplayer/LAN hosting, the default `voice_chat_target` is `127.0.0.1:25565`
- On dedicated servers, the default `voice_chat_target` remains `127.0.0.1:24454`
- `game`, `zstd`, `voice`, and `zstdvoice` can only be changed by the local host with admin permission
- This command does not modify dedicated server configs
- For dedicated servers, edit `config/zstdnet-server.properties` directly

## HUD Panel

After enabling `zstdhud`, you can view the following in-game:

- Current connection mode
- Listen address or remote target address
- Compressed real-time throughput
- Raw real-time throughput
- Total transferred traffic
- Compression ratio
- Current connection count

If you want to confirm whether the connection is actually using zstd, the HUD is the most direct way to check.

## FAQ

### Why can't I join the server?

Check these first:

1. If `auto_takeover=true`, players should keep using the usual public port from `server.properties`. If `auto_takeover=false`, make sure players are using the configured `listen` port.
2. Make sure FRP or other tunnels are forwarding to the `listen` port.
3. Make sure `enabled=true` is set in `config/zstdnet-server.properties`.
4. Make sure `listen` and `target` are not reversed.
5. Make sure the Zstd port is not already in use by another program.
6. Make sure no other mod is intercepting the login or handshake flow.
7. In LAN setups, use the Zstd port shown in chat. The game port can usually be left empty because ZstdNet follows the actual LAN session port automatically.

### Does a ratio near 100% mean it's not working?

Not necessarily.

Some traffic simply does not compress well, or may already be encrypted, which reduces the benefit significantly.

### Can this conflict with other multiplayer mods?

Possibly.

UI-heavy menu rewrites can still cause compatibility issues.

### What about Simple Voice Chat, Plasmo Voice or other UDP-based voice mods?

Yes — and it's **zero-config** (**on both mod servers and the plugin**). Voice audio is not compressed; it's forwarded as raw UDP. ZstdNet auto-detects the independent UDP port that common voice mods (Simple Voice Chat, Plasmo Voice) listen on, takes those ports over, and once a player joins it automatically opens matching listeners on the client's machine — you don't have to fill in any port. (The plugin build scans `plugins/voicechat/` and `plugins/PlasmoVoice/`, and pushes the ports to the client mod over a Bukkit plugin message.)

There are two voice transport modes, controlled by the server-side `voice_transport` setting:

- **`tunnel` (default)**: voice UDP also rides ZstdNet's public entry port (the same port as the game), and the server demultiplexes it to the backend voice ports. **This means you only need to expose one public port**, which is ideal for FRP/NAT-traversal setups that forward a single port.
- **`bridge`**: the client forwards voice UDP straight to the *real server's same port* with no extra server-side hop. This requires you to **port-forward/expose that voice UDP port separately**.

Notes:

- **Same-port voice mods** (Sable / Create Aeronautics, and SVC configured to follow the server port) already work — their UDP shares the game port and is covered by the built-in game passthrough.
- **Known limitation**: if an admin explicitly sets Simple Voice Chat's `voice_host` or Plasmo's `[host.public].ip` to a public address, the client connects there directly and **bypasses ZstdNet** (the server logs a WARN). Leave them at their defaults (blank / `0.0.0.0`) for ZstdNet to handle voice.
- **Other UDP mods**: for anything auto-detection misses, list the ports manually via `extra_udp_ports`.
- If the voice route fails to arm, the game TCP path still works; only voice goes offline.
- The legacy `voice_chat_listen` / `voice_chat_target` keys are kept for compatibility; `/zstdport voice <port>` and `/zstdport zstdvoice <port>` still work for manual tweaks.

> 📖 For a **step-by-step, no-prior-knowledge** walkthrough (how players join and talk / how owners open ports / tunnel vs bridge / a per-mod overview), see the **[Voice Mod Guide (beginner-friendly)](docs/voice.en-US.md)**.

## Configuration Files

- Client: `config/zstdnet-client.toml`
- Server: `config/zstdnet-server.properties`
- `zstdnet-server.properties` is auto-maintained. When ports are changed by commands or auto takeover, the file is rewritten with the built-in commented template.

**Configuration Item Explanation :**

- `enabled`：Whether to enable ZstdNet service (default: true)
  - Set to true to use Zstd compression

- `auto_takeover`：Whether to automatically use the `server-port` from `server.properties` as the public entry (default: true)
  - When enabled, server owners usually do not need to fill ports manually
  - ZstdNet will move the backend Minecraft server to another free local port during startup

- Dedicated auto mode：The config usually does not need fixed `listen` / `target` values
  - `listen` is resolved to the current public entry during startup
  - `target` is resolved to an automatically assigned local backend port during startup
  - Do not expose the runtime `target` directly to the internet

- `listen`：Zstd compressed entry address and port in manual mode
  - When `auto_takeover=false`, this is the address and port players should use
  - `0.0.0.0` means allow access from all IPs

- `target`：Backend Minecraft server address and port in manual mode
  - ZstdNet forwards compressed traffic to this address
  - `127.0.0.1` means local server

- `level`：Compression strength (1-22, recommended 3-9, default: 9)
  - Higher numbers mean better compression but use more CPU
  - Usually 3-5 is enough for a good balance of performance and compression

- `long_distance_matching`：Enable long-distance matching for highly repetitive servers (default: false)
  - Better ratio at the cost of extra per-connection memory; off by default
  - With `window_log` ≤ 27 it stays wire-compatible with existing (un-updated) clients

- `window_log`：LDM window as a power-of-two exponent (default: 0 = conservative 24, ~16MiB)
  - 24≈16MiB, 25≈32MiB, 27≈128MiB of memory per direction per connection
  - Only takes effect with `long_distance_matching=true`; values >27 require clients to enable a matching `window_log`

- `dictionary_auto`：**One-switch, fully automatic dictionary (recommended).** Set to true and you are done (default: false)
  - The server auto-samples live traffic, auto-trains once enough connections accumulate (~32), then auto-enables the dictionary and pushes it to players — no further file edits, no restart, no manual distribution
  - Players auto-download it on first join and are asked to reconnect once; afterwards they enjoy dictionary compression
  - Biggest win for the login registry/tag/recipe burst and streams of small packets
  - Explicit `dictionary` below takes precedence if set

- `dictionary`：(Manual) Trained dictionary file name under `config/zstdnet/dict/` (default: empty = off)
  - Use this only if you want to pin a specific dictionary file; otherwise prefer `dictionary_auto`
  - Automatically distributed to clients on first join (see "Compression Tuning & Dictionaries")

- `dictionary_capture`：(Manual build) Sample connection-start traffic into `config/zstdnet/dict/samples/` for training (default: false)
  - Not needed when `dictionary_auto=true`; turn it off once you have collected enough samples

- `dictionary_train`：(Manual build) One-shot training. Set to true and save to train `dict/trained.dict` from `samples/` (default: false)
  - Afterwards set `dictionary=trained.dict`, `dictionary_train=false`. Not needed when `dictionary_auto=true`

- `transform`：Entity packet-stream transform, for entity/mob-heavy scenes (default: false)
  - Before ZSTD, applies a **reversible de-interleaving** to the server→client stream: entity move/rotation/velocity fields and the high-repetition payloads of many similar mobs are grouped together, markedly improving compression for Create contraptions, mob farms/raids, etc.
  - **Only takes effect on a connection when the client also has `transform=true` and advertises support**; byte-for-byte compatible with un-upgraded / disabled clients (auto falls back to passthrough)
  - Dictionary connections prefer the dictionary (no transform on those); the two don't conflict
  - Correctness does not depend on the built-in packet table: a missing/wrong table only costs ratio, never corrupts (fail-closed)

- `transform_max_version`：Highest transform version (default: 3; effective = min of client/server)
  - `1`=version-agnostic de-interleaving only; `2`=+entity-move SoA columns; `3`=+mob-packet grouping
  - Versions 2/3 (entity-level gains) apply on covered MC versions (1.19.2 / 1.20.1 / 1.21.1); other versions auto-degrade to 1

- `transform_coalesce_ms`：(Reserved, not yet active) coalesce window in ms, currently always treated as 0 (default: 0)

- `max_conn_per_ip`：Maximum simultaneous connections per IP (default: 9999)
  - Set to 0 or negative to disable limit
  - Prevents a single IP from using too many connections

- `max_req_per_window`：Maximum requests per IP within a time window (default: 50)
  - Set to 0 or negative to disable limit
  - Prevents malicious request spamming

- `request_window`：Time range for request counting (default: 10s)
  - Works with max_req_per_window, e.g., max 50 requests within 10 seconds

- `ban_duration`：Ban duration after exceeding limits (default: 1m)
  - Prevents malicious attacks, banned IPs can't connect temporarily

- `stats_interval`：Interval for server logs to show traffic statistics (default: 0s, disables stats logs)
  - When set to a positive interval, periodically shows traffic information in the console

- `flush_interval`：How often to send compressed data (default: 2ms)
  - Set to 0 to send immediately after each compression
  - Smaller values mean lower latency but may increase network overhead

- `idle_timeout`：Backend connection idle timeout (default: 0)
  - Set to 0 to keep connections active indefinitely
  - Non-zero value means automatically close connections that are idle for longer than this time

- `max_rate_per_conn_bps`：Maximum speed limit per connection (default: 0)
  - Unit is bytes/second, set to 0 to disable limit
  - Prevents a single connection from using too much bandwidth

- `max_rate_global_bps`：Total speed limit for all connections (default: 0)
  - Unit is bytes/second, set to 0 to disable limit
  - Controls overall bandwidth usage

- `burst_bytes`：Allowed burst traffic size (default: 262144)
  - Unit is bytes, acts as a "buffer pool" for traffic
  - Allows short-term burst traffic to exceed the limit even with rate limiting enabled

- `voice_chat_passthrough`：Enable raw UDP passthrough for Simple Voice Chat (default: true)
  - Voice traffic is forwarded without zstd compression
  - Set to false to disable voice UDP handling entirely (no detection, takeover, or sync)

- `voice_transport`: voice/UDP mod transport mode (default: tunnel)
  - `tunnel`: voice also rides ZstdNet's public entry port (same port as the game); only one public port needs exposing
  - `bridge`: the client connects voice UDP straight to the real server's same port; you must expose that UDP port separately
  - Keep Simple Voice Chat's `voice_host` and Plasmo's `[host.public].ip` at their defaults (blank / `0.0.0.0`), or the client will bypass ZstdNet

- `extra_udp_ports`: additional UDP ports to pass through (comma-separated, default: empty)
  - For other UDP mods that auto-detection misses, e.g. `extra_udp_ports=24454,30000`
  - Empty means auto-detection only (Simple Voice Chat / Plasmo Voice)

- `voice_chat_listen`：Optional public UDP entry for voice chat (legacy manual config)
  - In singleplayer/LAN mode, the generated default is usually `0.0.0.0:24455`
  - In separate-port mode, this must be set explicitly

- `voice_chat_target`：Optional backend UDP target for voice chat
  - In singleplayer/LAN mode, the generated default is `127.0.0.1:25565`
  - On dedicated servers, the generated default is `127.0.0.1:24454`
  - In separate-port mode, when `voice_chat_listen` is set, it defaults to `127.0.0.1:<SVC port>`

### Client Configuration File `zstdnet-client.toml` Content

```toml
# Configuration file

[general]
	# zstd compression level for client->server stream
	level = 3
```

**Configuration Item Explanation:**

- `level`：Zstd compression level for client->server stream (default: 3, range: 1-22)
  - Higher levels provide better compression but increase CPU usage
  - It is recommended to choose between 3-5 to balance compression effect and performance

- `long_distance_matching`：Enable LDM for the client->server stream (default: false)
- `window_log`：LDM window exponent (default: 0 = conservative 24); only set >27 if the target server uses it too
- `dictionary`：Manual dictionary file under `config/zstdnet/dict/` (default: empty)
  - Usually unnecessary: dictionaries are auto-downloaded from servers that use them
- `transform`：Enable the entity packet-stream transform for servers that support it (default: false)
  - When enabled, the client advertises support in the handshake and installs a reverse decoder for the server→client stream
  - The connection is only actually transformed when the target server also has `transform=true`; otherwise it stays byte-for-byte compatible passthrough
  - Aimed at entity/mob-heavy servers (Create contraptions, mob farms), cutting downstream bandwidth substantially in those scenes

## Compression Tuning & Dictionaries

All of the options below are opt-in and off by default — the defaults keep the exact original behavior and stay compatible with un-updated clients.

### Long-distance matching (LDM)
For servers where the same large structures repeat over minutes (Create-based / large modpacks), set `long_distance_matching=true` (optionally `window_log=25`) on the server. With `window_log` ≤ 27 this is decodable by existing clients with no client update. Cost: roughly `2^window_log` bytes of memory per direction per connection, which adds up on a busy server — hence off by default.

### Entity packet-stream transform (for entity/mob-heavy servers)
When a server has **many entities** (Create contraptions moving at constant velocity, mob farms/raids packed with similar mobs), the high-repetition fields inside entity move/rotation/velocity packets and mob metadata are **interleaved** with structural bytes, so ZSTD can't match across packets and compression suffers.

With the transform enabled, the proxy applies a **reversible de-interleaving** to the server→client stream **before** feeding ZSTD: same-field values are grouped into contiguous columns (all entities' Δx together, entity IDs together, similar mobs' metadata placed adjacently), then **byte-exactly reconstructed** after decompression. Cross-tick repetition of the same field becomes a long match ZSTD can hit directly, leveraging the continuous frame (match history is kept across flushes) for a large ratio gain.

Usage: set **`transform=true` on both server and client** (off by default). Notes:
- With it off, behavior is byte-for-byte identical to before; if either side is un-upgraded / disabled, that connection auto-falls back to passthrough with no impact on connectivity or gameplay.
- Correctness does not depend on the built-in packet table: the table only guides the encoder's split; a missing/wrong table only costs ratio, never corrupts (fail-closed).
- Dictionary connections prefer the dictionary (no transform there); the two don't conflict.
- Entity-level gains (versions 2/3) cover 1.19.2 / 1.20.1 / 1.21.1; other versions auto-degrade to version-agnostic de-interleaving (version 1).

### Dictionaries (zero-config auto-distribution)
A trained ZSTD dictionary gives the biggest gain for the login burst (registry/tags/recipes) and for streams of small, similar packets (entity moves, block updates).

**Recommended — one switch, fully automatic:** set `dictionary_auto=true` on the server. That's the whole setup. The server then, on its own:
1. samples live connection-start traffic in the background (each connection is split into several small chunk-samples, since dictionary training needs a minimum number of samples),
2. after just a couple of player connections' worth of samples, trains `config/zstdnet/dict/trained.dict` on a background thread,
3. enables the dictionary **live (hot-swapped, without disconnecting any current session)** and pushes it to **all online players plus new joiners** — no second edit, no restart, no manual file distribution.

Players who receive the dictionary are asked to reconnect once (a clear in-game message); from then on they are dictionary-compressed. Players who already have it are not kicked. The server keeps running normally the whole time (it just isn't dictionary-compressing yet during the short learning phase). Once trained, the dictionary is permanent and used for every later connection.

You do **not** need 7-8 people to repeatedly join and leave — normal first-session joins are enough to reach the training threshold.

<details><summary>Manual workflow (advanced — only if you want to control training explicitly)</summary>

1. Set `dictionary_capture=true`, let players connect for a while (samples accumulate in `config/zstdnet/dict/samples/`), then set `dictionary_capture=false`.
2. Set `dictionary_train=true` and save — `config/zstdnet/dict/trained.dict` is produced (or train externally with the `zstd --train` CLI).
3. Set `dictionary=trained.dict` and `dictionary_train=false`.

</details>

Players need no setup in either case. When they join, the server announces its dictionary; a client that does not yet have it downloads it (≤1 MB), caches it under `config/zstdnet/dict/auto/`, maps it to that server, and is disconnected once with a message asking it to rejoin. From the next connection the dictionary is used from the very first packet. Later joins already have it and connect dictionary-compressed with no kick.

Note: if you later change the server's dictionary, players who cached the old one may need to delete `config/zstdnet/dict/auto/` once to recover.

## Dependencies

- zstd-jni

## License

This project is licensed under the MIT License.
