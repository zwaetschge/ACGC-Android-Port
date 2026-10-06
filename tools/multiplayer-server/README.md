# Multiplayer server (town visits)

Animal Crossing on the GameCube has one kind of multiplayer: **visiting a friend's town**. You put your friend's
memory card in slot B, take the train to their town, play there and come home. Their card remembers the visit.

This small server replaces handing the memory card around. There is no central server. Anyone can host one
for their friends, and it is a single Python file with no dependencies.

```
 Owner (Anna)                   server                    Visitor (Ben)
 "Share my town"  ── town.gci ──▶  code K7Q2MX
                                     │        ◀── "Visit a town" K7Q2MX ──  town goes into memory card B
                                     │                                     train ride, play, train home, save
                                     │        ◀── "Send town back" ─────────
 "Get my town back" ◀── updated town ┘
```

While the town is away, the owner should not play it. Fetching the town back replaces the owner's save with
the visited one, just like getting the memory card back. A backup is kept in `files/save-backups/`.

## Hosting

Pick one of these three ways.

### Docker (recommended, e.g. on a NAS / Unraid / Raspberry Pi)

```sh
cd tools/multiplayer-server
docker compose up -d --build
```

Without compose:

```sh
docker build -t acgc-relay tools/multiplayer-server
docker run -d --name acgc-relay --restart unless-stopped -p 8765:8765 -v "$PWD/acgc-data:/data" acgc-relay
```

On **Unraid**, add a container from `python:3.12-alpine`:
- mount `acgc_relay.py` to `/app/acgc_relay.py`
- set `/data` to an appdata folder
- map port 8765
- use the command `python3 -u /app/acgc_relay.py`

Or build the image as above.

### Plain Python (any PC, 3.8 or newer)

```sh
python3 tools/multiplayer-server/acgc_relay.py --port 8765 --data ./acgc-data
```

### Options

| Flag | Environment variable | Default | Meaning |
|---|---|---|---|
| `--port` | `ACGC_PORT` | 8765 | TCP port |
| `--data` | `ACGC_DATA` | `./data` | where shared towns are stored |
| `--key` | `ACGC_KEY` | none | server key that every player must enter in the app, which keeps strangers out |
| `--days` | `ACGC_DAYS` | 7 | how long a shared town is kept |
| `--max-towns` | `ACGC_MAX_TOWNS` | 500 | storage limit |

Setting a key is recommended as soon as the server is reachable from the internet.

## Letting your friends connect

- **Same Wi-Fi:** use the host's LAN address, e.g. `http://192.168.1.10:8765`.
- **[Tailscale](https://tailscale.com) (easiest for friends elsewhere):** install Tailscale on the host and on
  every phone and share the machine with your friends. Then use `http://<tailscale-ip>:8765`. You don't need
  port forwarding, and nothing is public.
- **Port forwarding:** forward TCP 8765 on your router to the host and use `http://<your-public-ip>:8765`.
  Set `--key` in this case.
- **Tunnel (no router access):** for example `cloudflared tunnel --url http://localhost:8765` or
  [playit.gg](https://playit.gg). Use the HTTPS address the tunnel prints.

Check it from a browser: `http://<address>:8765/v1/health` should show `{"ok": true, ...}`.

## Playing

In the Android launcher, open the **Play together** card. Save and quit the game first.

1. Everyone: **Set up server** and enter the address (and key, if any).
2. Owner: **Share my town**. Send the 6-character code to your friend.
3. Visitor: **Visit a town** and enter the code. The town is now in memory card B.
   - Start the game.
   - Talk to the station attendant (Porter) and choose to travel to another town.
   - Play, take the train home and save.
4. Visitor: **Send town back**.
5. Owner: **Get my town back**. Your save is replaced by the visited town, and a backup is kept.

PC port players can do the same with `acgc_client.py`; the game reads card B from `save/card_b/`:

```sh
python3 acgc_client.py --server http://host:8765 share  save/card_a/DobutsunomoriP_MURA.gci
python3 acgc_client.py --server http://host:8765 visit  K7Q2MX save/card_b/DobutsunomoriP_MURA.gci
python3 acgc_client.py --server http://host:8765 return K7Q2MX save/card_b/DobutsunomoriP_MURA.gci
python3 acgc_client.py --server http://host:8765 fetch  K7Q2MX <token> save/card_a/DobutsunomoriP_MURA.gci
python3 acgc_client.py --server http://host:8765 delete K7Q2MX <token>   # withdraw the shared town
```

Save files are compatible between the Android port, the PC port and Dolphin (GCI format). Both towns must come
from the same game version (for example both USA).

## API (v1)

All requests may need the header `X-Server-Key` when the server has a key. Bodies are raw GCI files (≤ 1 MiB,
game code `GAF?01`).

| Method | Path | Result |
|---|---|---|
| GET | `/v1/health` | `{"ok": true, "version": 1, "key_required": bool}` |
| POST | `/v1/towns` | share a town, returns `{"code", "token", "expires_in"}` |
| GET | `/v1/towns/{code}` | the shared town |
| GET | `/v1/towns/{code}/info` | `{"code", "town", "player", "created", "returned", "visitor"}` |
| PUT | `/v1/towns/{code}/return` | visitor sends the visited town back |
| GET | `/v1/towns/{code}/return` | owner fetches it (header `X-Owner-Token: <token>`) |
| DELETE | `/v1/towns/{code}` | owner withdraws the town (header `X-Owner-Token`) |

The server stores only the uploaded save files and deletes them after `--days`. It runs over plain HTTP; use
Tailscale or an HTTPS tunnel if you don't want saves to cross the internet unencrypted.
