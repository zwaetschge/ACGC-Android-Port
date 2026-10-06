#!/usr/bin/env python3
"""Command-line client for acgc_relay.py (PC port players, testing).

    acgc_client.py --server http://host:8765 share  save/card_a/DobutsunomoriP_MURA.gci
    acgc_client.py --server http://host:8765 visit  CODE save/card_b/DobutsunomoriP_MURA.gci
    acgc_client.py --server http://host:8765 return CODE save/card_b/DobutsunomoriP_MURA.gci
    acgc_client.py --server http://host:8765 fetch  CODE TOKEN returned.gci
    acgc_client.py --server http://host:8765 info   CODE
    acgc_client.py --server http://host:8765 delete CODE TOKEN
"""
import argparse
import json
import sys
import urllib.error
import urllib.request


def request(args, method, path, data=None, headers=None):
    h = dict(headers or {})
    if args.key:
        h["X-Server-Key"] = args.key
    if data is not None:
        h["Content-Type"] = "application/octet-stream"
    req = urllib.request.Request(args.server.rstrip("/") + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read()).get("error")
        except ValueError:
            msg = e.reason
        sys.exit("error %d: %s" % (e.code, msg))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--server", required=True)
    ap.add_argument("--key", default="")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("share"); s.add_argument("gci"); s.add_argument("--town", default=""); s.add_argument("--player", default="")
    s = sub.add_parser("visit"); s.add_argument("code"); s.add_argument("out")
    s = sub.add_parser("return"); s.add_argument("code"); s.add_argument("gci"); s.add_argument("--player", default="")
    s = sub.add_parser("fetch"); s.add_argument("code"); s.add_argument("token"); s.add_argument("out")
    s = sub.add_parser("info"); s.add_argument("code")
    s = sub.add_parser("delete"); s.add_argument("code"); s.add_argument("token")
    a = ap.parse_args()

    if a.cmd == "share":
        with open(a.gci, "rb") as f:
            r = json.loads(request(a, "POST", "/v1/towns", f.read(), {"X-Town": a.town, "X-Player": a.player}))
        print("code:  %s\ntoken: %s  (keep it to fetch the returned town)" % (r["code"], r["token"]))
    elif a.cmd == "visit":
        with open(a.out, "wb") as f:
            f.write(request(a, "GET", "/v1/towns/" + a.code.upper()))
        print("saved", a.out)
    elif a.cmd == "return":
        with open(a.gci, "rb") as f:
            request(a, "PUT", "/v1/towns/%s/return" % a.code.upper(), f.read(), {"X-Player": a.player})
        print("returned")
    elif a.cmd == "fetch":
        with open(a.out, "wb") as f:
            f.write(request(a, "GET", "/v1/towns/%s/return" % a.code.upper(), headers={"X-Owner-Token": a.token}))
        print("saved", a.out)
    elif a.cmd == "info":
        print(request(a, "GET", "/v1/towns/%s/info" % a.code.upper()).decode())
    elif a.cmd == "delete":
        request(a, "DELETE", "/v1/towns/" + a.code.upper(), headers={"X-Owner-Token": a.token})
        print("deleted")


if __name__ == "__main__":
    main()
