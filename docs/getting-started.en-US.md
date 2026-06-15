# ZstdNet Getting Started

ZstdNet compresses Minecraft multiplayer traffic to save bandwidth. **It only works if both the server and the player install it.**

Supported: Forge 1.19.2 / 1.20.1, NeoForge 1.20.1 / 1.21.1, Fabric 1.20.1 / 1.21.1; one plugin jar covers all.

## Player (joining a server)

1. Drop the **ZstdNet client mod** into `mods/` (match the server's loader and version).
2. Join: on a mod server use the address as usual; on a plugin server use the port the owner gives you (usually `IP:gameport+1`, e.g. `IP:25566`).
3. Type `/zstdhud on` in-game to see compression. If you have a voice mod it connects automatically — see the [voice guide](voice.en-US.md).

## Owner: mod server (Forge / NeoForge / Fabric)

1. Drop **ZstdNet** into `mods/`.
2. Set `online-mode=false` in `server.properties` and start. It auto-takes over the port; players keep using the original port.
3. Open `server-port` in your firewall (**TCP+UDP**).

## Owner: plugin server (Paper / Spigot / Purpur / hybrid)

1. Drop `zstdnet-bukkit-<version>.jar` into `plugins/` and start.
2. It generates `plugins/ZstdNet/zstdnet-server.properties`; the entry port = game port + 1 (e.g. `25566`).
3. Open that entry port (**TCP+UDP**). ZstdNet players connect to `IP:25566`; others keep using `25565`.
4. Admin commands (OP): `/zstdnet status | reload | start | stop`.

## More

Full options (compression level, FRP, real IP, dictionaries, …) are in the [full docs](../README.en-US.md). 中文: [getting-started.zh-CN.md](getting-started.zh-CN.md).
