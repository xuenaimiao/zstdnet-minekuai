# Voice Mod Guide (Beginner-Friendly)

> This page shows you how to get **voice mods** (Simple Voice Chat, Plasmo Voice, …) working on a server that uses **ZstdNet**.
> No networking background needed — just follow along. Unknown terms are in the [Glossary](#glossary).

ZstdNet is a mod that **compresses Minecraft multiplayer traffic to save bandwidth**. It takes over the connection between you and the server — which, in the past, could **accidentally block a voice mod's port**, so you'd join the server but couldn't hear or talk.
**ZstdNet now has built-in zero-config voice support.** In almost all cases you **don't have to configure anything** and voice just works. This guide explains that "almost all cases", plus how to troubleshoot if it doesn't.

---

## 30-second summary: what do I actually do?

| You are a… | What to do |
|---|---|
| **Player** (just want to join a server and talk) | ① Install the **ZstdNet client mod** ② Install the **same voice mod as the server** (e.g. Simple Voice Chat) ③ Join normally. **Voice connects automatically — no ports to configure.** |
| **Server owner** (hosting for others) | Install ZstdNet (mod or plugin build) + a voice mod. **The default is already zero-config.** For a public server you only need to **open your entry port (allow BOTH TCP and UDP)** in your firewall / port forwarding. See [For server owners](#for-server-owners). |

> **Most common setup:** public server + Simple Voice Chat. The [Player](#for-players-client-side) and [Server owner](#for-server-owners) sections are all you need.

---

## A few basics first (read this if you're new)

Skip this if you already know ports, UDP, and public IPs.

- **Port**: think of the server as a building — **the IP is the street address, a port is a room number**. The game uses one room (Minecraft default `25565`); a voice mod usually uses another (Simple Voice Chat default `24454`).
- **TCP vs UDP**: two "delivery methods". **The game runs over TCP** (reliable, signed-for); **voice runs over UDP** (fast, dropping a packet or two is fine for real-time talk). Firewalls treat them separately, which is why this guide keeps saying "**allow both TCP and UDP**".
- **Public IP / port forwarding**: to let outside players reach your server, a port must be "opened to the outside". On a cloud server this is a **security-group rule**; on home internet it's **router port forwarding**; with tunneling tools (FRP, ngrok, …) it's **mapping/forwarding a port**. Same idea: **open a port number to the outside world**.
- **Why did voice mods break before?**
  A voice mod works like this: after you join, the server quietly tells your client "**send voice to port xxx**", and your voice mod sends audio there.
  To compress, ZstdNet "reroutes" your connection through a local relay. The voice mod then sends audio to a port where **nobody is listening** — so you can't hear or talk.
- **How does ZstdNet fix it now?** (You don't need the details, just the result.)
  The server side of ZstdNet **auto-detects** which port the voice mod uses, and the moment a player joins it **automatically tells** the client's ZstdNet, which **automatically wires up that port locally**. No manual port entry anywhere.

---

## The voice mods at a glance

| Voice mod | Default port | Works out of the box? | Notes |
|---|---|---|---|
| **Simple Voice Chat** (most common) | `24454` (independent UDP port) | ✅ now auto-supported | The main mod this feature fixes. It defaults to an independent port 24454, which used to be blocked and is now taken over automatically. |
| **Plasmo Voice** | **follows the game port by default** (same-port) | ✅ always worked; custom port also auto-supported | Defaults to the same port as the game, so it was never affected. If an admin gives it a dedicated port, that's now auto-handled too. |
| **Same-port mods**: Sable, Create: Aeronautics, etc. | same as the game port | ✅ always worked | Their UDP already shares the game port and is covered by ZstdNet's built-in passthrough — nothing extra needed. |
| **Other / niche UDP mods** | varies | ⚠️ may need one extra line | If auto-detection misses it, the owner adds the port via `extra_udp_ports` (below). |

> In one line: **a server with Simple Voice Chat now does voice out of the box**; Plasmo / Sable / Aeronautics were never a problem.

---

## For players (client side)

This is the easy part — **just three steps, and zero ports to configure**:

1. **Install the ZstdNet client mod**: drop it into `mods/` like any mod. Match the server's game version + loader (Forge / NeoForge / Fabric).
2. **Install the voice mod**: install the **same voice mod as the server** (if the server runs Simple Voice Chat, so do you), matching version.
3. **Join normally**:
   - **Mod server**: enter the address/port exactly as you would without ZstdNet.
   - **Plugin server (Paper/Spigot, …)**: the owner will give you a "**ZstdNet entry port**" (usually game port + 1, e.g. `serverIP:25566`). Use that address to get compression.

Voice **connects automatically** after you join. You do **not** need to change any "server address / port" setting inside the voice mod.

> Want to confirm compression is active? Type `/zstdhud on` in-game to see compression stats. Unrelated to voice — just fun.

---

## For server owners

Bottom line: **the default config is already zero-config for voice — you barely touch anything.** The only thing to handle is **firewall / opening the port**.

ZstdNet comes in two forms; pick the one matching your server type.

### A) Mod server (Forge / NeoForge / Fabric)

1. Put **ZstdNet** and your **voice mod** (e.g. Simple Voice Chat) into `mods/`.
2. Start the server normally. ZstdNet auto-takes over the port (`auto_takeover`) and **auto-detects** the voice mod's port.
3. The config is `config/zstdnet-server.properties`, defaulting to **`voice_transport=tunnel`** — voice and game **share one public port**.
4. **Open the port**: just allow your **public entry port** (usually the `server-port` in `server.properties`, e.g. `25565`) in your firewall / port forwarding, **both TCP and UDP**. Done.

### B) Plugin server (Paper / Spigot / Purpur / Arclight / Mohist, …)

1. Drop **`zstdnet-bukkit-<version>.jar`** into `plugins/`, install the voice **plugin** (e.g. the Bukkit build of Simple Voice Chat), and start.
2. First start generates `plugins/ZstdNet/zstdnet-server.properties`, defaulting to:
   ```properties
   auto_takeover=false
   listen=0.0.0.0:25566      # ZstdNet public entry port (default = server-port + 1)
   target=127.0.0.1:25565    # your local Minecraft backend port
   voice_transport=tunnel    # default tunnel: voice shares the entry port
   ```
3. The plugin scans `plugins/voicechat/` and `plugins/PlasmoVoice/` for the voice port and **pushes it to the client automatically** when a player joins (no effect on vanilla players without ZstdNet).
4. **Open the port**: allow the `listen` port (e.g. `25566`), **both TCP and UDP**. Vanilla players keep using `25565`; ZstdNet players use `25566`.

> Plugin tip: if you installed the ZstdNet plugin **before** the voice plugin, the voice port may not be detected yet after a restart. Run `/zstdnet reload` once, or restart the server.

### tunnel or bridge? (two voice transport modes)

`voice_transport` controls how voice travels. **Default is `tunnel`; beginners should keep the default.**

| Mode | How it travels | Ports to open | Best for |
|---|---|---|---|
| **`tunnel`** (default, recommended) | Voice UDP is **carried inside the game's single entry port**, demuxed by the server | **Only the entry port** (TCP+UDP) | Almost everyone — especially **FRP / tunneling / "I can only open one port"** |
| **`bridge`** | The client connects voice UDP **directly to the real server's voice port** | Entry port + **a separate voice port** (UDP) | You can open ports freely and want voice to skip the relay |

Analogy: **tunnel = voice and game use the same door** (simplest); **bridge = a separate door for voice** (one more port to open).

To switch, set `voice_transport=bridge` in the config; the server hot-reloads within a few seconds (or `/zstdnet reload`). With bridge, remember to **also open the voice port (e.g. 24454/UDP) to the public**.

### How do I actually open a port?

- **Cloud server (AWS, GCP, Aliyun, …)**: add a **security-group** rule allowing your entry port, protocol **TCP and UDP (or All)**.
- **Home internet**: log into your router, find **Port Forwarding / Virtual Server / NAT**, forward the entry port to your host machine, **TCP+UDP**.
- **Tunneling (FRP, ngrok, …)**: map/forward your entry port. **In tunnel mode you only need one port** — that's the whole point of tunnel mode.

---

## Config quick reference (owners)

All in `zstdnet-server.properties` (mod server: `config/`; plugin server: `plugins/ZstdNet/`):

- **`voice_transport`**: voice transport, `tunnel` (default) or `bridge`. See above.
- **`extra_udp_ports`**: manually add UDP ports to pass through, comma-separated. **Fallback for niche voice/UDP mods that auto-detection misses.** e.g. `extra_udp_ports=24454,30000`. Empty = auto-detection only.
- **`voice_chat_passthrough`**: enable voice UDP forwarding, default `true`. **Setting `false` disables voice handling entirely** (no detect / take-over / push). Usually leave it alone.

> Voice traffic is **never compressed** — it's forwarded as-is (audio barely compresses, and compressing would only add latency). Compression applies to the game itself.

---

## Troubleshooting

**Q: I joined but can't hear / can't talk.**
1. Make sure **client and server have the same voice mod, matching versions**.
2. Make sure the owner **opened the entry port with BOTH TCP and UDP** (the classic mistake is opening TCP only and forgetting UDP).
3. Plugin server: if the voice plugin was installed after ZstdNet, run `/zstdnet reload` once or restart.
4. Check the server log for `voice ports detected ...` or a `WARN ... voice_host ...` line (see "Known limitation").

**Q: Voice is one-way / choppy.**
- Usually **UDP isn't fully open** or there's packet loss. Confirm UDP is allowed; with FRP/tunneling, confirm you're forwarding UDP (or TCP+UDP).

**Q: I use a niche voice mod that isn't detected.**
- Owner adds `extra_udp_ports=<that mod's port>` to the config, saves, then `/zstdnet reload`.

**Q: Do Plasmo / Sable / Aeronautics need special setup?**
- No. They default to the game port and already work.

---

## Known limitation (when it won't work)

ZstdNet can take over voice only if the **voice mod tells the client to send audio to "the server's connection address"** (the default behavior).
**The one case it can't:** an admin **manually** sets the voice mod's "public address" to a **specific public IP** —

- Simple Voice Chat: `voice_host` is filled in `voicechat-server.properties`;
- Plasmo Voice: `[host.public].ip` in `config.toml` is set to a specific public IP (not `0.0.0.0`).

Then the client **connects directly to that address and bypasses ZstdNet** (the server logs a `WARN`). That's an admin-requested direct connection, mutually exclusive with "relayed through ZstdNet".
**To let ZstdNet handle voice, leave those at their defaults (blank / `0.0.0.0`).**

---

## Glossary

- **Mod loader**: Forge / NeoForge / Fabric — the base that runs mods. Client and server must use the same one.
- **Mod server / plugin server**: a mod server runs a loader (`mods/` folder); a plugin server is Paper/Spigot-style (`plugins/` folder). Hybrid servers (Arclight/Mohist) accept both — using the ZstdNet plugin build is recommended there.
- **Port**: a "room number" on the server distinguishing services. Game and voice use different ports.
- **TCP / UDP**: two network transports. Game over TCP, voice over UDP. Open them separately.
- **Entry / listen port**: the port ZstdNet exposes publicly and that players actually connect to.
- **Backend / target port**: your real local Minecraft server port, used only internally on the host.
- **Port forwarding / opening a port**: making a port reachable from the outside.
- **tunnel / bridge**: ZstdNet's two voice transport modes — see above.
- **Public connection / FRP / tunneling**: ways to let outside players reach your server.

---

For full server/client configuration, FRP topology, compression levels, dictionaries, etc., see the [full documentation](../README.en-US.md).
