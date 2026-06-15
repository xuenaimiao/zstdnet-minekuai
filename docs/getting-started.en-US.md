# ZstdNet Getting Started (beginner-friendly, no experience needed)

> This guide is for people who have **never installed a mod or run a server before**. It explains
> everything in plain language, step by step. For full parameter details, see
> [`README.en-US.md`](../README.en-US.md) (the complete manual for advanced users).

---

## 1. What is this? (one sentence + an analogy)

**ZstdNet compresses your Minecraft multiplayer traffic to save public bandwidth and reduce lag.**

Think of shipping a big box of clothes: you vacuum-pack it first so it's smaller and cheaper to ship,
and the other end unpacks it with nothing missing. Game data works the same way — it's compressed
before being sent and restored on the other side. **What you see and play is exactly the same**, only
the network traffic in between is much smaller.

Good fits:
- **Modpack servers** and **Create-based servers** (lots of repetitive data);
- Servers run through **frp / NAT traversal** (hosting from a home PC for outside friends);
- Servers with **small public bandwidth** or **metered/pay-per-traffic** connections.

> ⚠️ **The single most important thing**: compression takes *both* ends. The sender compresses and the
> receiver decompresses — **it only works if both sides have it installed.** One side alone does nothing.

---

## 2. First figure out "who you are" — it decides what to install

| Your situation | What to install |
| --- | --- |
| I'm a **regular player** joining a ZstdNet server | The **client mod** (see [section 3](#3-for-players-how-to-join-a-zstdnet-server)) |
| I run a **Forge / NeoForge / Fabric server** (needs a mod loader) | The **same mod** on the server too (see [4-A](#a-mod-server-forge--neoforge--fabric)) |
| I run a **Paper / Spigot / Purpur server** (plugin server, no mod loader) | The **plugin build** (see [4-B](#b-plugin-server-paper--spigot--purpur)) |
| I run a **hybrid server** like **Arclight / Mohist** | Either the plugin or the mod; the plugin is recommended (see [4-C](#c-hybrid-servers-arclight--mohist-etc)) |

### Quick glossary (read this if the table above had unfamiliar words)

- **Client / Server**: your own gaming PC is the "client"; the machine everyone connects to is the "server".
- **Mod loader**: the base layer that lets the game load mods — commonly **Forge, NeoForge, Fabric**. You need one before installing mods.
- **Plugin server**: a server run on **Paper / Spigot / Purpur**. These **don't take mods**, only "plugins".
- **Hybrid server**: takes **both mods and plugins**, e.g. Arclight, Mohist.
- **Port**: think of it as the server's "door number" — the number after `:` in a connect address (e.g. `25565`).

---

## 3. For players: how to join a ZstdNet server

### Step 1: Know your game version and loader

In your launcher, check which loader (**Forge / NeoForge / Fabric**) and version (e.g. `1.20.1`) you'll play.
This repository currently provides these client builds: **Forge 1.19.2, Forge 1.20.1, NeoForge 1.20.1,
NeoForge 1.21.1, Fabric 1.20.1, Fabric 1.21.1**.

### Step 2: Put the client mod into the `mods` folder

1. Download the ZstdNet `.jar` that matches your **loader + version**.
2. Find the game's `mods` folder (usually `.minecraft\mods`, or inside your modpack instance's folder).
   If it doesn't exist, launch once with the loader installed and it will be created.
3. Drag the `.jar` into the `mods` folder.
4. Launch the game with that loader version.

### Step 3: Join

- **If the host runs a mod server or hybrid server**: connect normally with the address they gave you.
- **If the host runs a plugin server**: the **port may be different** (e.g. `25566` instead of `25565`).
  **Use whatever the host tells you.**

### Step 4: Confirm compression is actually working

In game, type `/zstdhud` to toggle a small panel showing the compression ratio and stats.
**A lower ratio is better** (e.g. 30% means traffic shrank to a third). If the numbers are moving, it's working.

> ❓ **Can I join without the mod?** Yes — you connect on the normal port and play as usual, just **without
> compression**. It doesn't affect your gameplay.

---

## 4. For server owners: how to enable ZstdNet

### A. Mod server (Forge / NeoForge / Fabric)

1. Put the **server-side** ZstdNet `.jar` for your version into the server's `mods` folder.
2. Start the server normally. **It works out of the box**: it takes over the `server-port` from your
   `server.properties`, so players **keep connecting to the same address and port** — nothing to change.
3. (Optional) Want to tune it? After the first start it generates `config/zstdnet-server.properties`,
   with comments on every line. **Defaults are fine for most people.**

> `auto_takeover=true` (the default) means ZstdNet takes over the public port itself and quietly moves the
> real game server to another local port, so **you don't configure ports manually.**

### B. Plugin server (Paper / Spigot / Purpur)

> Key difference: plugins load *after* the server has started, when the port is already taken — so a plugin
> **can't** take over the original port the way a mod does. The plugin build uses a **separate port** instead.

1. Put `zstdnet-bukkit-x.x.x.jar` into the server's `plugins` folder (**one jar supports both 1.20.1 and
   1.21.x** — no need to pick a version).
2. Start the server. It generates `plugins/ZstdNet/zstdnet-server.properties` with these defaults:
   ```properties
   enabled=true
   auto_takeover=false
   listen=0.0.0.0:25566      # ZstdNet public entry (defaults to game port + 1)
   target=127.0.0.1:25565    # your real local game port
   ```
3. **Open the `listen` port (e.g. 25566) in your firewall / security group.**
4. Tell your players:
   - **Players with ZstdNet** → connect to `yourIP:25566` (the `listen` port) for compression;
   - **Vanilla players without the mod** → keep connecting to `yourIP:25565` (the game port), unaffected.

**Admin commands** (require OP):

| Command | What it does |
| --- | --- |
| `/zstdnet status` | Show state, ports, connections, compression ratio |
| `/zstdnet reload` | Reload after editing config (config edits also hot-reload within a few seconds) |
| `/zstdnet start` | Start the proxy |
| `/zstdnet stop` | Stop the proxy |

> Tip: the `listen` port must differ from the game port and be free. Edit that line to change it. Keep
> `auto_takeover=false` on plugin servers.

### C. Hybrid servers (Arclight / Mohist, etc.)

Hybrids run Forge/NeoForge underneath and accept **both mods and plugins**, so either works:

- **Simplest**: use the **plugin build** (drop into `plugins/`) — it doesn't depend on the hybrid's
  low-level injection compatibility.
- You can also use the matching **mod build** (drop into `mods/`). If auto port-takeover fails, set
  `auto_takeover=false` in the mod's config and fill in `listen` / `target` manually, or just use the plugin.

---

## 5. A little more advanced: frp / NAT traversal (skip if unfamiliar)

**frp / NAT traversal** maps a port on your home PC to the public internet so outsiders can connect.

When configuring it, remember one rule: **point the public tunnel at ZstdNet's entry port, not the raw game
port** — otherwise you bypass compression.

Typical chain:

```
player  →  public frp port  →  ZstdNet entry port on your PC  →  local game port
```

If your modpack includes **Sable / Create Aeronautics** or other "same-port UDP" mods, make sure the tunnel
forwards **both TCP and UDP**. Voice mods (e.g. Simple Voice Chat) are auto-supported, usually with no extra setup.

---

## 6. FAQ (what beginners worry about most)

**Q: Will I get banned / is it cheating?**
No. ZstdNet only compresses the data between you and the server. It **changes no game logic and no game
content** — the server receives exactly the same thing as vanilla.

**Q: Will it make me lag more?**
Compression uses very little CPU; its job is to **save bandwidth**. The default level `9` already balances
effect and cost.

**Q: Can friends without the mod still join my server?**
Yes. They connect on the normal game port as usual, just without compression.

**Q: `/zstdhud` shows a ratio near 100% — is it broken?**
A 100% ratio means "this data couldn't be compressed further" (e.g. already-compressed content). As more
data flows, the ratio usually drops. If the panel shows activity, it's working.

**Q: I can't join — what now?**
Check, in order: ① address and port (plugin servers use the host's `listen` port); ② your loader and version
match the server; ③ the host opened the `listen` port in the firewall.

**Q: Does it conflict with other multiplayer / voice mods?**
ZstdNet works at the network transport layer and normally doesn't conflict with gameplay mods; UDP-based voice
mods like Simple Voice Chat are auto-supported.

---

## 7. Tiny glossary

| Term | Plain meaning |
| --- | --- |
| Client | your own gaming PC |
| Server | the machine everyone connects to |
| Port | the server's "door number" — the number after `:` in an address |
| Mod loader | the base that lets the game load mods (Forge / NeoForge / Fabric) |
| Plugin | an extension for Paper/Spigot-style servers (not a mod) |
| Hybrid server | a server that takes both mods and plugins (Arclight / Mohist, etc.) |
| frp / NAT traversal | maps a home-PC port to the public internet so outsiders can connect |
| Compression ratio | compressed size as a % of the original — **lower is better** |
| HUD | the little info panel in game (toggle with `/zstdhud`) |

---

For advanced topics — full parameters, PROXY protocol, real IP, dictionary compression — see
[`README.en-US.md`](../README.en-US.md). Chinese guide: [`getting-started.zh-CN.md`](getting-started.zh-CN.md).
