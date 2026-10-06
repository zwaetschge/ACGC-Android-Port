#!/usr/bin/env python3
"""Town exchange server for the Animal Crossing (GameCube) Android/PC port.

Animal Crossing's own multiplayer is the town visit: a friend's memory card in
slot B, a train ride to their town, and their card is updated when you leave.
This server replaces carrying the card around. A player shares their town and
gets a short code; a friend downloads the town with that code (it becomes their
memory card B), visits it in-game and sends the updated town back; the owner
then fetches the returned town.

Standard library only (Python 3.8+). Run it yourself, see README.md.

    python3 acgc_relay.py --port 8765 --data ./data [--key SECRET]
"""
import argparse
import hmac
import json
import os
import re
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = 1
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I
CODE_LEN = 6
MAX_BODY = 1 << 20  # a GCI town save is 467,008 bytes
CODE_RE = re.compile(r"^[%s]{%d}$" % (CODE_ALPHABET, CODE_LEN))


class Store:
    """One directory per town code: town.gci, return.gci, meta.json."""

    def __init__(self, root, ttl, max_towns):
        self.root = root
        self.ttl = ttl
        self.max_towns = max_towns
        self.lock = threading.Lock()
        os.makedirs(root, exist_ok=True)

    def _dir(self, code):
        return os.path.join(self.root, code)

    def meta(self, code):
        try:
            with open(os.path.join(self._dir(code), "meta.json"), encoding="utf-8") as f:
                m = json.load(f)
        except (OSError, ValueError):
            return None
        if time.time() - m["created"] > self.ttl:
            self.delete(code)
            return None
        return m

    def _write_meta(self, code, m):
        tmp = os.path.join(self._dir(code), "meta.json.tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(m, f)
        os.replace(tmp, os.path.join(self._dir(code), "meta.json"))

    def _write(self, code, name, data):
        tmp = os.path.join(self._dir(code), name + ".tmp")
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, os.path.join(self._dir(code), name))

    def read(self, code, name):
        try:
            with open(os.path.join(self._dir(code), name), "rb") as f:
                return f.read()
        except OSError:
            return None

    def expire(self):
        for code in os.listdir(self.root):
            if CODE_RE.match(code):
                self.meta(code)  # deletes expired entries

    def create(self, data, town, player):
        with self.lock:
            self.expire()
            if len([c for c in os.listdir(self.root) if CODE_RE.match(c)]) >= self.max_towns:
                raise OverflowError("server is full")
            while True:
                code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LEN))
                if not os.path.exists(self._dir(code)):
                    break
            os.makedirs(self._dir(code))
            token = secrets.token_urlsafe(24)
            self._write(code, "town.gci", data)
            self._write_meta(code, {"created": time.time(), "token": token, "town": town,
                                    "player": player, "returned": None, "visitor": None})
            return code, token

    def put_return(self, code, data, visitor):
        with self.lock:
            m = self.meta(code)
            if m is None:
                return False
            self._write(code, "return.gci", data)
            m["returned"] = time.time()
            m["visitor"] = visitor
            self._write_meta(code, m)
            return True

    def delete(self, code):
        d = self._dir(code)
        for name in ("town.gci", "return.gci", "meta.json", "town.gci.tmp", "return.gci.tmp", "meta.json.tmp"):
            try:
                os.remove(os.path.join(d, name))
            except OSError:
                pass
        try:
            os.rmdir(d)
        except OSError:
            pass


def valid_gci(data):
    # GCI header: game code "GAF?" + maker "01"
    return 0x40 < len(data) <= MAX_BODY and data[:3] == b"GAF" and data[4:6] == b"01"


def clean(value, limit=32):
    return re.sub(r"[^\w .'!?-]", "", value or "", flags=re.UNICODE)[:limit]


class Handler(BaseHTTPRequestHandler):
    server_version = "acgc-relay/%d" % VERSION
    store = None
    key = None

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)

    def _send(self, status, body=b"", ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _error(self, status, msg):
        self._send(status, {"error": msg})

    def _authorized(self):
        if not self.key:
            return True
        given = self.headers.get("X-Server-Key", "")
        return hmac.compare_digest(given.encode(), self.key.encode())

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_BODY:
            return None
        return self.rfile.read(n)

    def _route(self):
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if len(parts) < 2 or parts[0] != "v1":
            return None, None, None
        code = parts[2].upper() if len(parts) > 2 and parts[1] == "towns" else None
        if code is not None and not CODE_RE.match(code):
            code = ""
        return parts[1], code, parts[3] if len(parts) > 3 else None

    def _owner(self, m):
        return hmac.compare_digest(self.headers.get("X-Owner-Token", "").encode(), m["token"].encode())

    def do_GET(self):
        what, code, sub = self._route()
        if what == "health":
            return self._send(200, {"ok": True, "version": VERSION, "key_required": bool(self.key)})
        if not self._authorized():
            return self._error(401, "wrong or missing server key")
        if what != "towns" or not code:
            return self._error(404, "not found")
        m = self.store.meta(code)
        if m is None:
            return self._error(404, "unknown or expired code")
        if sub is None:
            data = self.store.read(code, "town.gci")
            return self._send(200, data, "application/octet-stream") if data else self._error(404, "missing")
        if sub == "info":
            return self._send(200, {"code": code, "town": m["town"], "player": m["player"],
                                    "created": int(m["created"]), "returned": m["returned"] is not None,
                                    "visitor": m["visitor"]})
        if sub == "return":
            if not self._owner(m):
                return self._error(403, "only the town owner can fetch the returned town")
            data = self.store.read(code, "return.gci")
            return self._send(200, data, "application/octet-stream") if data else self._error(404, "not returned yet")
        return self._error(404, "not found")

    def do_POST(self):
        what, code, sub = self._route()
        if not self._authorized():
            return self._error(401, "wrong or missing server key")
        if what != "towns" or code is not None:
            return self._error(404, "not found")
        data = self._body()
        if data is None or not valid_gci(data):
            return self._error(400, "body must be an Animal Crossing GCI save (max 1 MiB)")
        try:
            code, token = self.store.create(data, clean(self.headers.get("X-Town")),
                                            clean(self.headers.get("X-Player")))
        except OverflowError as e:
            return self._error(507, str(e))
        self._send(201, {"code": code, "token": token, "expires_in": self.store.ttl})

    def do_PUT(self):
        what, code, sub = self._route()
        if not self._authorized():
            return self._error(401, "wrong or missing server key")
        if what != "towns" or not code or sub != "return":
            return self._error(404, "not found")
        data = self._body()
        if data is None or not valid_gci(data):
            return self._error(400, "body must be an Animal Crossing GCI save (max 1 MiB)")
        if not self.store.put_return(code, data, clean(self.headers.get("X-Player"))):
            return self._error(404, "unknown or expired code")
        self._send(200, {"ok": True})

    def do_DELETE(self):
        what, code, sub = self._route()
        if not self._authorized():
            return self._error(401, "wrong or missing server key")
        if what != "towns" or not code or sub is not None:
            return self._error(404, "not found")
        m = self.store.meta(code)
        if m is None:
            return self._error(404, "unknown or expired code")
        if not self._owner(m):
            return self._error(403, "only the town owner can delete it")
        self.store.delete(code)
        self._send(200, {"ok": True})


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--host", default=os.environ.get("ACGC_HOST", "0.0.0.0"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("ACGC_PORT", "8765")))
    ap.add_argument("--data", default=os.environ.get("ACGC_DATA", "./data"))
    ap.add_argument("--key", default=os.environ.get("ACGC_KEY", ""),
                    help="optional server key; clients must send it (keeps strangers out)")
    ap.add_argument("--days", type=float, default=float(os.environ.get("ACGC_DAYS", "7")),
                    help="how long shared towns are kept")
    ap.add_argument("--max-towns", type=int, default=int(os.environ.get("ACGC_MAX_TOWNS", "500")))
    a = ap.parse_args()
    Handler.store = Store(a.data, a.days * 86400, a.max_towns)
    Handler.key = a.key or None
    httpd = ThreadingHTTPServer((a.host, a.port), Handler)
    print("acgc-relay listening on %s:%d, data in %s, key %s" %
          (a.host, a.port, os.path.abspath(a.data), "required" if a.key else "not set"), flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
