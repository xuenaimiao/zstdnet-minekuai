# Voice Mod Guide

With ZstdNet installed, **voice mods like Simple Voice Chat and Plasmo Voice work automatically, zero-config** (same on mod servers and plugin servers). Same-port voice mods like Sable and Create: Aeronautics already work.

## Player

Install the **same voice mod as the server**, join normally, and voice connects automatically. **Don't change any address / port inside the voice mod.**

## Server owner

The default is already zero-config. The only thing to do is **open the entry port, allowing BOTH TCP and UDP** (the classic mistake is opening TCP only).

- Mod server: open `server-port` from `server.properties`.
- Plugin server: open the `listen` port in `zstdnet-server.properties`; the plugin auto-scans `plugins/voicechat/` and `plugins/PlasmoVoice/`.

`voice_transport` (in `zstdnet-server.properties`, default `tunnel`):

- `tunnel`: voice shares the game's entry port — **open just one port** (recommended, ideal for FRP / tunneling).
- `bridge`: voice connects directly to the real voice port — you must **open that voice port (UDP) separately**.

For niche UDP mods that aren't detected, add `extra_udp_ports=<port>` and run `/zstdnet reload`.

## Can't hear / can't talk — checklist

1. Do client and server have the same voice mod, matching versions?
2. Is the entry port's **UDP** actually open?
3. Plugin server: if the voice plugin was installed after ZstdNet, run `/zstdnet reload` once or restart.

## Known limitation

If an admin sets Simple Voice Chat's `voice_host` or Plasmo's `[host.public].ip` to a specific public address, the client bypasses ZstdNet. To let ZstdNet handle voice, **leave them at their defaults (blank / `0.0.0.0`)**.
