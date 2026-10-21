#!/usr/bin/env python3
"""fakeflow: a pretend StationFlow, speaking the TV API, with no radio.

    python3 tools/fakeflow.py                      # http://127.0.0.1:18300/icom/
    python3 tools/fakeflow.py --tls                # https on 18343, CA on http 18380
    FAKEFLOW_PASSWORD= python3 tools/fakeflow.py   # no password: open, as an unpublished module

What it serves is StationFlow's API as icom/docs/TV-API.md (feature/
icom-rx-audio of dock360-apps, final version) describes it - the paths, the
payload shapes, the ACCESS_PASSWORD form login and its 401s, and the ack flow
control on audio and scope - with the radio replaced by arithmetic:

- state on /api/ws: an IC-9700 on 2 m, S-meter following how close the dial
  is to one of the synthetic signals, pushed on every change and once a
  second; /fake/model?key=ic-7610 or ic-705 swaps in another radio (its
  modes, band buttons and ranges, as the module publishes them) and
  /fake/onair?on=1 shows the radio transmitting as if something else had
  keyed it, so a client's model-specific layout and its TX indicators can be
  seen without a radio;
- tuning (/api/freq, mode, data, filter, vfo, split) changes that state and
  echoes it back on the socket, with the module's own refusals (range, 503
  when the radio is "switched off" via /fake/radio);
- receive audio on /api/audio/rx (and 1.23.0's /api/audio): a 1000 Hz tone
  at -10 dBFS plus a little noise, 12 kHz s16le mono, 20 ms messages,
  paced to real time - something a test can measure the frequency of. With
  ?ack=1 the client acks bytes received and no more than window_ms is kept
  in flight (the rest is skipped, as the module does);
- the band scope on /api/scope: 475 points, 50 kHz centred on the dial, a
  noise floor and three signals at fixed RF frequencies (145.500 FM carrier,
  144.174 FT8 cluster, 144.300 SSB), ten sweeps a second, each with a seq;
  with ?ack=1 no more than two frames are in flight;
- decodes on /api/digi/ws every 15 s, spots built from them, APRS stations,
  and an SSTV picture "received" every FAKEFLOW_SSTV_EVERY seconds (line
  events, a live.png that grows, then the finished picture).

It never transmits because there is nothing to transmit with - but it does
record every request that would have, refuses it as a module with
TX_ENABLED off does (403), and serves the list at /fake/posts (open, a test
hook): a test drives the app and then checks that no PTT or transmit-queue
path was ever asked for. StationFlow does not enforce levels yet (the
platform will), so neither does this.

Environment: FAKEFLOW_PORT (18300), FAKEFLOW_PREFIX (/icom), FAKEFLOW_PASSWORD
(fakeflow; empty for none, as an unpublished module), FAKEFLOW_SSTV_EVERY (60), FAKEFLOW_PIDFILE
(~/build/fakeflow-sftv-<port>.pid). Stop it with kill $(cat <pidfile>); never
pkill -f, which matches every python on the host.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import datetime
import hashlib
import hmac
import io
import ipaddress
import json
import logging
import math
import os
import random
import struct
import time
from pathlib import Path

from aiohttp import WSMsgType, web

log = logging.getLogger("fakeflow")

PASSWORD = os.environ.get("FAKEFLOW_PASSWORD", "fakeflow")
PREFIX = os.environ.get("FAKEFLOW_PREFIX", "/icom").rstrip("/")
SSTV_EVERY = float(os.environ.get("FAKEFLOW_SSTV_EVERY", "60"))
# Fixed per password, like the module's SECRET_KEY setting: sessions survive
# a restart of the fake, and changing the password ends them.
SECRET = hashlib.sha256(b"fakeflow-secret:" + PASSWORD.encode()).digest()
COOKIE = "icom9700"
RATE = 12000

# Paths that would key the radio or queue a transmission. Never served; always recorded.
TX_PATHS = {"/api/ptt", "/api/digi/arm", "/api/digi/tx", "/api/digi/reply", "/api/digi/cq",
            "/api/digi/tune", "/api/digi/config", "/api/digi/cancel", "/api/digi/abort", "/api/audio/tx"}

# The synthetic band: (rf_hz, width_hz, level 0..1, what)
SIGNALS = [
    (145_500_000, 12_000, 0.85, "FM carrier"),
    (144_174_000 + 600, 60, 0.55, "FT8"), (144_174_000 + 1400, 60, 0.45, "FT8"),
    (144_174_000 + 2100, 60, 0.6, "FT8"),
    (144_300_000 + 800, 2_400, 0.5, "SSB voice"),
    # HF, for the IC-7610 and IC-705 (/fake/model): FT8 on 20 m and 40 m, a CW
    # signal and an SSB one, so a switched radio's scope has something on it.
    (14_074_000 + 900, 60, 0.6, "FT8"), (14_074_000 + 1600, 60, 0.45, "FT8"),
    (14_060_000, 150, 0.7, "CW"), (14_285_000 + 900, 2_400, 0.55, "SSB voice"),
    (7_074_000 + 1200, 60, 0.6, "FT8"), (7_074_000 + 2000, 60, 0.5, "FT8"),
    (7_090_000 + 800, 2_400, 0.6, "SSB voice"),
]

CALLS = [("G0ABC", "IO91", "England"), ("PA3XYZ", "JO22", "Netherlands"), ("DL1QRP", "JO62", "Germany"),
         ("GM4XYZ", "IO85", "Scotland"), ("F5LMN", "IN88", "France"), ("EI2AB", "IO63", "Ireland"),
         ("ON4TV", "JO20", "Belgium"), ("G4KLX", "IO92", "England")]


# The three radios, as icom/app/radios.py and registry.band_buttons describe
# them (feature/icom-rx-audio). The state a client gets differs by model in
# exactly these fields - name, model key, modes, band buttons, transmit ranges
# - and in nothing else: StationFlow publishes the same fields for every radio,
# so this is all a client can use to tell an IC-7610 from an IC-9700, and all a
# radio-shaped display can be laid out from.
_VU = ["LSB", "USB", "AM", "CW", "RTTY", "FM", "CWR", "RTTYR"]
_HF = [(1_810_000, 2_000_000, "160m", 1_840_000), (3_500_000, 3_800_000, "80m", 3_600_000),
       (5_351_500, 5_366_500, "60m", 5_357_000), (7_000_000, 7_200_000, "40m", 7_090_000),
       (10_100_000, 10_150_000, "30m", 10_136_000), (14_000_000, 14_350_000, "20m", 14_074_000),
       (18_068_000, 18_168_000, "17m", 18_100_000), (21_000_000, 21_450_000, "15m", 21_074_000),
       (24_890_000, 24_990_000, "12m", 24_915_000), (28_000_000, 29_700_000, "10m", 28_074_000)]
_SIX = [(50_000_000, 52_000_000, "6m", 50_313_000)]
_FOUR = [(70_000_000, 70_500_000, "4m", 70_200_000)]
_TWO = [(144_000_000, 146_000_000, "2m", 144_300_000)]
_70 = [(430_000_000, 440_000_000, "70cm", 432_200_000)]
_23 = [(1_240_000_000, 1_300_000_000, "23cm", 1_296_200_000)]
MODELS = {
    "ic-9700": {"name": "IC-9700", "modes": _VU + ["DV", "DD"], "bands": _TWO + _70 + _23,
                "watts": {144_000_000: 100, 430_000_000: 75, 1_240_000_000: 10},
                "rx": [(144_000_000, 146_000_000), (430_000_000, 440_000_000), (1_240_000_000, 1_300_000_000)],
                "start": (144_174_000, "USB", True)},
    "ic-7610": {"name": "IC-7610", "modes": _VU + ["PSK", "PSKR"], "bands": _HF + _SIX, "watts": {},
                "rx": [(30_000, 60_000_000)], "start": (14_074_000, "USB", True)},
    "ic-705": {"name": "IC-705", "modes": _VU + ["WFM", "DV"], "bands": _HF + _SIX + _FOUR + _TWO + _70,
               "watts": {}, "rx": [(30_000, 199_999_999), (400_000_000, 470_000_000)],
               "start": (7_074_000, "USB", True)},
}
_DATA_CAPABLE = {"LSB", "USB", "AM", "FM"}


def model_fields(key: str) -> dict:
    """The state fields that come from the model, as registry.Live.public() builds them."""
    m = MODELS[key]
    default_w = 10 if key == "ic-705" else 100
    return {
        "name": m["name"],
        "modes": list(m["modes"]),
        "data_modes": [x for x in m["modes"] if x in _DATA_CAPABLE],
        "bands": [{"name": n, "hz": spot} for _, _, n, spot in m["bands"]],
        "model": {"key": key, "name": m["name"], "bands": [
            {"from": lo, "to": hi, "watts": m["watts"].get(lo, default_w)} for lo, hi, _, _ in m["bands"]]},
    }


class Fake:
    def __init__(self):
        self.radio = {
            "id": "r2813", "name": "IC-9700",
            "connected": True, "link_state": "connected", "error": None, "wanted": True,
            "freq": 144_174_000, "mode": "USB", "data_mode": True, "mode_label": "USB-D",
            "filter": "FIL1", "vfo": "VFOA", "split": False, "ptt": False,
            "smeter": 40, "smeter_db": -36.0, "power": None, "swr": None,
            "audio": True, "audio_rate": RATE, "scope": True, "scope_points": 475,
            "digi": {"mode": "FT8", "armed": False, "transmitting": False, "holder": "FT8"},
        }
        self.radio.update(model_fields("ic-9700"))
        self.digi_holder = "FT8"
        self.switching = False
        self.state_sockets: set = set()
        self.digi_sockets: set = set()
        self.aprs_sockets: set = set()
        self.sstv_sockets: set = set()
        self.decodes: list[dict] = []
        self.spots: dict[str, dict] = {}
        self.aprs_stations = self._aprs_seed()
        self.pictures: list[dict] = []
        self.picture_png: dict[str, bytes] = {}
        self.live_png: bytes | None = None
        self.sstv_running = True
        self.posts: list[dict] = []          # every POST, for /fake/posts
        self.tx_attempts: list[str] = []     # every transmit path asked for
        self.audio_listeners = 0
        self.scope_listeners = 0
        self.audio_last: dict = {}     # the last closed audio socket's flow figures
        self.scope_last: dict = {}
        self._seed_pictures()

    # -- auth -------------------------------------------------------------

    def token(self, expires: int) -> str:
        mac = hmac.new(SECRET, str(expires).encode(), hashlib.sha256).hexdigest()
        return f"{expires}.{mac}"

    def signed_in(self, request) -> bool:
        """The module's cookie check: an HMAC'd expiry (app/main.py _valid)."""
        if not PASSWORD:
            return True
        tok = request.cookies.get(COOKIE, "")
        expires, _, _ = tok.partition(".")
        try:
            if int(expires) < time.time():
                return False
        except ValueError:
            return False
        return hmac.compare_digest(self.token(int(expires)), tok)

    # -- state ------------------------------------------------------------

    def meter(self):
        """S-meter from the strongest synthetic signal inside the passband."""
        f = self.radio["freq"]
        width = {"FM": 12_000, "AM": 6_000, "CW": 500}.get(self.radio["mode"], 2_700)
        best = 0.0
        for hz, w, lvl, _ in SIGNALS:
            lo, hi = f - (width if self.radio["mode"] == "LSB" else 0), f + width
            if self.radio["mode"] in ("FM", "AM"):
                lo, hi = f - width / 2, f + width / 2
            if lo - w <= hz <= hi + w:
                best = max(best, lvl)
        raw = 15 + best * 200 + random.uniform(-4, 4)
        raw = max(0, min(241, raw))
        db = (raw / 120 * 54 - 54) if raw <= 120 else (raw - 120) / 121 * 60
        self.radio["smeter"] = int(raw)
        self.radio["smeter_db"] = round(db, 1)

    def public_state(self) -> dict:
        r = dict(self.radio)
        r["audio_stats"] = {"enabled": True, "rate": RATE, "running": True, "listeners": self.audio_listeners}
        r["digi"] = {**r["digi"], "mode": self.digi_holder, "holder": self.digi_holder}
        return {
            "title": self.radio["name"], "version": "1.24.0-fake", "tx_enabled": False,
            "server_time": time.time(), "selected": "r2813", "switching": "r2813" if self.switching else None,
            "digi_modes": ["FT8", "FT4", "JS8", "WSPR"],
            "radios": {"r2813": r} if self.radio["wanted"] else {},
        }

    async def push_state(self):
        for ws in list(self.state_sockets):
            try:
                await ws.send_json(self.public_state())
            except Exception:
                self.state_sockets.discard(ws)

    # -- decodes ------------------------------------------------------------

    def make_decodes(self) -> list[dict]:
        now = time.time()
        period = int(now // 15)
        utc = time.strftime("%H%M%S", time.gmtime(period * 15))
        out = []
        for i in range(random.randint(3, 5)):
            call, grid, country = random.choice(CALLS)
            to, _, _ = random.choice(CALLS)
            kind = random.choice(["cq", "cq", "exchange", "report"])
            msg = (f"CQ {call} {grid}" if kind == "cq" else
                   f"{to} {call} {grid}" if kind == "exchange" else f"{to} {call} -{random.randint(5, 20):02d}")
            audio = random.choice([600, 1400, 2100, 900, 1750]) + random.randint(-20, 20)
            dial = self.radio["freq"]
            d = {"mode": self.digi_holder or "FT8", "utc": utc, "snr": random.randint(-22, 5),
                 "dt": round(random.uniform(-0.3, 0.8), 1), "freq_hz": audio, "message": msg,
                 "when": period * 15.0, "period": period, "period_half": "even" if period % 2 == 0 else "odd",
                 "dial_hz": dial, "rf_hz": dial + audio, "from": call, "to": None if kind == "cq" else to,
                 "kind": kind, "grid": grid, "calling_cq": kind == "cq", "for_me": False, "country": country}
            out.append(d)
            self.spots[call] = {"call": call, "first": now, "last": now, "count": self.spots.get(call, {}).get("count", 0) + 1,
                                "snr": d["snr"], "mode": d["mode"], "message": msg, "kind": kind, "grid": grid,
                                "freq_hz": d["rf_hz"], "audio_hz": audio, "band": "2m", "country": country,
                                "distance_km": random.randint(80, 1400)}
        self.decodes.extend(out)
        self.decodes = self.decodes[-400:]
        return out

    # -- APRS -------------------------------------------------------------

    def _aprs_seed(self):
        now = time.time()
        return [
            {"call": "GB7XYZ-10", "first": now - 600, "last": now - 30, "packets": 12, "kind": "position",
             "symbol": "#", "symbol_name": "Digipeater", "comment": "Fake digi, 144.800", "path": "WIDE2-1",
             "distance_km": 22},
            {"call": "M0ABC-9", "first": now - 300, "last": now - 5, "packets": 4, "kind": "position",
             "symbol": ">", "symbol_name": "Car", "comment": "Mobile, QRV 145.500", "distance_km": 8},
            {"call": "G4WX", "first": now - 900, "last": now - 120, "packets": 9, "kind": "weather",
             "symbol": "_", "symbol_name": "Weather station", "status": "12.3C 1012hPa"},
        ]

    def aprs_view(self):
        now = time.time()
        stations = []
        for s in self.aprs_stations:
            s2 = dict(s)
            s2["age_s"] = round(now - s["last"], 1)
            s2["stale"] = s2["age_s"] > 60
            stations.append(s2)
        stations.sort(key=lambda s: s["last"], reverse=True)
        return {"radio": "r2813", "radio_name": self.radio["name"], "stations": stations, "packets": [],
                "placed": 0, "unplaced": len(stations), "counters": {"packets": 25},
                "radio_state": {"ok": True, "problems": []}, "at": now}

    # -- SSTV -------------------------------------------------------------

    def _picture(self, n: int, rows: int | None = None) -> bytes:
        from PIL import Image, ImageDraw, ImageFont
        w, h = 320, 256
        img = Image.new("RGB", (w, h), "black")
        d = ImageDraw.Draw(img)
        bars = ["#FFFFFF", "#FFFF00", "#00FFFF", "#00FF00", "#FF00FF", "#FF0000", "#0000FF", "#000000"]
        for i, c in enumerate(bars):
            d.rectangle([i * w // 8, 0, (i + 1) * w // 8, 150], fill=c)
        try:
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 28)
            small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 16)
        except OSError:
            font = small = ImageFont.load_default()
        call = CALLS[n % len(CALLS)][0]
        d.text((12, 160), f"{call}  SSTV #{n}", font=font, fill="white")
        d.text((12, 205), f"fakeflow test card · {time.strftime('%H:%M UTC', time.gmtime())}", font=small, fill="#FFD54F")
        if rows is not None:
            d.rectangle([0, rows, w, h], fill="black")
        buf = io.BytesIO()
        img.save(buf, "PNG")
        return buf.getvalue()

    def _seed_pictures(self):
        # Oldest first, as they would have arrived: each is put at the front.
        for n in range(3, 0, -1):
            self._add_picture(4 - n, at=time.time() - 3600 * n)

    def _add_picture(self, n: int, at: float | None = None) -> dict:
        at = at or time.time()
        pid = time.strftime("%Y%m%d-%H%M%S", time.gmtime(at)) + f"_Robot36-{n}"
        rec = {"id": pid, "file": pid + ".png", "at": at - 36, "finished_at": at, "mode": "Robot36",
               "width": 320, "height": 256, "lines": 240, "expected_lines": 240, "complete": True,
               "radio": "r2813", "freq_hz": 145_500_000}
        self.picture_png[pid] = self._picture(n)
        self.pictures.insert(0, rec)
        return rec

    def sstv_view(self):
        return {"radio": "r2813", "radio_name": self.radio["name"], "counters": {"pictures": len(self.pictures)},
                "decoder": {"state": "listening" if self.sstv_running else "stopped"},
                "pictures": self.pictures[:50], "usage": {}, "modes": [], "radio_state": {"ok": True}, "at": time.time()}


fake = Fake()


def rel(request) -> str:
    """The path below the prefix: /icom/api/freq -> /api/freq."""
    p = request.path
    if PREFIX and p.startswith(PREFIX):
        p = p[len(PREFIX):] or "/"
    return p


@web.middleware
async def auth_mw(request, handler):
    p = rel(request)
    if request.method == "POST":
        fake.posts.append({"path": p, "at": time.time()})
        if p in TX_PATHS:
            fake.tx_attempts.append(p)
            log.warning("TRANSMIT PATH ASKED FOR: %s", p)
    if p in ("/healthz", "/login", "/api/login", "/.well-known/openhamshack") or p.startswith("/fake/"):
        return await handler(request)
    if not fake.signed_in(request):
        if p.startswith("/api/"):
            return web.json_response({"error": "not signed in"}, status=401)
        raise web.HTTPFound(PREFIX + "/login")
    return await handler(request)


# -- routes -------------------------------------------------------------------

async def healthz(request):
    return web.json_response({"ok": True, "radio": fake.radio["connected"]})




async def login(request):
    """The module's form login (app/main.py login): a redirect either way."""
    data = await request.post()
    if not PASSWORD or data.get("password") != PASSWORD:
        raise web.HTTPFound(PREFIX + "/login?bad=1")
    expires = int(time.time()) + 30 * 86400
    resp = web.HTTPFound(PREFIX + "/")
    resp.set_cookie(COOKIE, fake.token(expires), max_age=30 * 86400, httponly=True, samesite="Lax",
                    path=PREFIX + "/")
    raise resp


async def login_page(request):
    return web.Response(text="<form method=post action=api/login><input name=password type=password></form>",
                        content_type="text/html")


async def state(request):
    return web.json_response(fake.public_state())


async def state_ws(request):
    ws = web.WebSocketResponse(heartbeat=25)
    await ws.prepare(request)
    fake.state_sockets.add(ws)
    try:
        await ws.send_json(fake.public_state())
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
    finally:
        fake.state_sockets.discard(ws)
    return ws


def no_radio():
    return web.json_response({"error": "not connected to the radio"}, status=503)


async def set_freq(request):
    body = await request.json()
    try:
        hz = int(body["hz"])
    except (KeyError, TypeError, ValueError):
        return web.json_response({"error": "hz must be a whole number"}, status=400)
    if not fake.radio["connected"]:
        return no_radio()
    rx = MODELS[fake.radio["model"]["key"]]["rx"]
    if not any(lo <= hz <= hi for lo, hi in rx):
        span = ", ".join(f"{lo / 1e6:g} to {hi / 1e6:g}" for lo, hi in rx)
        return web.json_response({"error": f"{fake.radio['name']} tunes {span} MHz"}, status=400)
    fake.radio["freq"] = hz
    fake.meter()
    await fake.push_state()
    return web.json_response({"ok": True})


async def set_mode(request):
    body = await request.json()
    if not fake.radio["connected"]:
        return no_radio()
    label = str(body.get("mode", ""))
    mode, data = (label[:-2], True) if label.endswith("-D") else (label, bool(body.get("data", False)))
    if mode not in fake.radio["modes"]:
        return web.json_response({"error": f"no such mode: {mode}"}, status=400)
    if data and mode not in fake.radio["data_modes"]:
        return web.json_response({"error": f"{fake.radio['name']} has no {mode}-D"}, status=400)
    fake.radio.update(mode=mode, data_mode=data, mode_label=mode + ("-D" if data else ""))
    fake.meter()
    await fake.push_state()
    return web.json_response({"ok": True})


async def set_data(request):
    body = await request.json()
    on = bool(body.get("on"))
    m = fake.radio["mode"]
    fake.radio.update(data_mode=on, mode_label=m + ("-D" if on else ""))
    await fake.push_state()
    return web.json_response({"ok": True})


async def set_filter(request):
    body = await request.json()
    f = str(body.get("filter", "")).upper()
    if f not in ("FIL1", "FIL2", "FIL3"):
        return web.json_response({"error": "filter must be FIL1, FIL2 or FIL3"}, status=400)
    fake.radio["filter"] = f
    await fake.push_state()
    return web.json_response({"ok": True})


async def set_vfo(request):
    body = await request.json()
    v = str(body.get("vfo", "")).upper()
    if v not in ("VFOA", "VFOB"):
        return web.json_response({"error": "VFOA or VFOB"}, status=400)
    fake.radio["vfo"] = v
    await fake.push_state()
    return web.json_response({"ok": True})


async def set_split(request):
    body = await request.json()
    fake.radio["split"] = bool(body.get("on"))
    await fake.push_state()
    return web.json_response({"ok": True})


async def refuse_tx(request):
    # As the module answers with TX_ENABLED off. Recorded by the middleware.
    return web.json_response({"error": "transmitting is switched off in this module's settings"}, status=403)


async def audio_ws(request):
    """1000 Hz at -10 dBFS plus noise, 12 kHz s16le mono, 20 ms per message, real time.

    ?ack=1: the client sends {"ack": <bytes received>}; no more than window_ms
    (default 500) is kept in flight and anything beyond is skipped - a gap,
    never a delay - as TV-API.md section 7 describes.
    """
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    fake.audio_listeners += 1
    ack = request.query.get("ack") == "1"
    window_ms = max(100, min(3000, int(request.query.get("window_ms", "500") or 500)))
    window = RATE * 2 * window_ms // 1000
    hdr = {"direction": "rx", "rate": RATE, "encoding": "s16le", "format": "pcm16", "channels": 1,
           "radio_id": "r2813"}
    if ack:
        hdr.update(flow="ack", window_bytes=window)
    await ws.send_json(hdr)
    state = {"acked": 0, "skipped": 0}

    async def read():
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    state["acked"] = max(state["acked"], int(json.loads(msg.data).get("ack", 0)))
                except (ValueError, TypeError, AttributeError):
                    pass
            elif msg.type == WSMsgType.ERROR:
                break

    reader = asyncio.ensure_future(read())
    n = RATE // 50
    phase = 0.0
    step = 2 * math.pi * 1000 / RATE
    amp = 32767 * 10 ** (-10 / 20)
    t0 = time.monotonic()
    produced = 0
    sent_bytes = 0
    try:
        while not ws.closed and not reader.done():
            samples = []
            for _ in range(n):
                samples.append(int(amp * math.sin(phase) + random.gauss(0, 300)))
                phase += step
            phase %= 2 * math.pi
            produced += n
            if ack and sent_bytes - state["acked"] >= window:
                state["skipped"] += 1
            else:
                await ws.send_bytes(struct.pack(f"<{n}h", *[max(-32768, min(32767, s)) for s in samples]))
                sent_bytes += 2 * n
            ahead = t0 + produced / RATE - time.monotonic()
            if ahead > 0:
                await asyncio.wait({reader}, timeout=ahead)
    except (ConnectionResetError, asyncio.CancelledError):
        pass
    finally:
        reader.cancel()
        fake.audio_listeners -= 1
        fake.audio_last = {"acked": state["acked"], "sent": sent_bytes, "skipped_chunks": state["skipped"], "ack": ack}
    return ws


async def scope_ws(request):
    """Ten sweeps a second with a seq; ?ack=1 keeps at most two in flight (TV-API.md section 8)."""
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    fake.scope_listeners += 1
    ack = request.query.get("ack") == "1"
    span = 50_000
    hdr = {"enabled": True, "radio_id": "r2813", "running": True, "points": 475, "max": 160,
           "mode": "center", "refused": None}
    if ack:
        hdr["flow"] = "ack"
    await ws.send_json(hdr)
    state = {"acked": 0, "held": 0}

    async def read():
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    state["acked"] = max(state["acked"], int(json.loads(msg.data).get("ack", 0)))
                except (ValueError, TypeError, AttributeError):
                    pass
            elif msg.type == WSMsgType.ERROR:
                break

    reader = asyncio.ensure_future(read())
    seq = 0
    try:
        while not ws.closed and not reader.done():
            if ack and seq - state["acked"] >= 2:
                state["held"] += 1            # this sweep is dropped; the next replaces it
            else:
                seq += 1
                c = fake.radio["freq"]
                start, end = c - span // 2, c + span // 2
                data = []
                for i in range(475):
                    hz = start + (end - start) * i / 474
                    v = 22 + random.gauss(0, 4)
                    for shz, w, lvl, _ in SIGNALS:
                        d = (hz - shz) / max(w / 2, 60)
                        if abs(d) < 6:
                            v = max(v, 22 + lvl * 120 * math.exp(-d * d) + random.gauss(0, 3))
                    data.append(int(max(0, min(160, v))))
                await ws.send_json({"receiver": 0, "mode": "center", "start": start, "end": end,
                                    "out_of_range": False, "max": 160, "points": 475, "data": data,
                                    "radio_id": "r2813", "seq": seq})
            await asyncio.wait({reader}, timeout=0.1)
    except (ConnectionResetError, asyncio.CancelledError):
        pass
    finally:
        reader.cancel()
        fake.scope_listeners -= 1
        fake.scope_last = {"seq": seq, "acked": state["acked"], "held": state["held"], "ack": ack}
    return ws


def digi_state():
    return {"mode": fake.digi_holder, "modes": ["FT8", "FT4", "JS8", "WSPR"], "holder": fake.digi_holder,
            "decoded": len(fake.decodes), "server_time": time.time()}


async def digi(request):
    return web.json_response(digi_state())


async def digi_history(request):
    return web.json_response({"decodes": fake.decodes[-200:]})


async def digi_ws(request):
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    fake.digi_sockets.add(ws)
    try:
        await ws.send_json({"state": digi_state(), "decodes": fake.decodes[-50:]})
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
    finally:
        fake.digi_sockets.discard(ws)
    return ws


async def digi_mode(request):
    body = await request.json()
    mode = str(body.get("mode", ""))
    if fake.digi_holder and fake.digi_holder != mode and not body.get("force"):
        return web.json_response({"error": f"{fake.digi_holder} is using the decoder", "busy": fake.digi_holder},
                                 status=409)
    fake.digi_holder = mode
    for ws in list(fake.digi_sockets):
        await ws.send_json({"state": digi_state()})
    return web.json_response({"ok": True, **digi_state()})


async def digi_stop(request):
    fake.digi_holder = None
    for ws in list(fake.digi_sockets):
        await ws.send_json({"state": digi_state()})
    return web.json_response({"ok": True})


async def spots(request):
    now = time.time()
    out = []
    for s in sorted(fake.spots.values(), key=lambda s: s["last"], reverse=True):
        s2 = dict(s)
        s2["age_s"] = round(now - s["last"], 1)
        s2["stale"] = s2["age_s"] > 60
        out.append(s2)
    return web.json_response({"station": {"callsign": "GM4BRI", "locator": "IO75RL"}, "spots": out,
                              "fresh_s": 60, "keep_s": 1800, "at": now})


async def aprs(request):
    return web.json_response(fake.aprs_view())


async def aprs_ws(request):
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    fake.aprs_sockets.add(ws)
    try:
        await ws.send_json({"state": fake.aprs_view()})
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
    finally:
        fake.aprs_sockets.discard(ws)
    return ws


async def aprs_channels(request):
    return web.json_response({"channels": [{"name": "Europe", "hz": 144_800_000, "mode": "FM"}]})


async def ok(request):
    return web.json_response({"ok": True})


async def sstv(request):
    return web.json_response(fake.sstv_view())


async def sstv_start(request):
    fake.sstv_running = True
    return web.json_response({"ok": True, **fake.sstv_view()})


async def sstv_pictures(request):
    return web.json_response({"pictures": fake.pictures, "usage": {}})


async def sstv_picture(request):
    pid = request.match_info["pid"]
    png = fake.picture_png.get(pid)
    if png is None:
        return web.json_response({"error": "no such picture"}, status=404)
    return web.Response(body=png, content_type="image/png")


async def sstv_live(request):
    if fake.live_png is None:
        return web.json_response({"error": "nothing coming in"}, status=404)
    return web.Response(body=fake.live_png, content_type="image/png")


async def sstv_ws(request):
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    fake.sstv_sockets.add(ws)
    try:
        await ws.send_json({"state": fake.sstv_view()})
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
    finally:
        fake.sstv_sockets.discard(ws)
    return ws


async def well_known(request):
    """The platform's app directory (PLATFORM-PLAN section 2.1), as a OpenHamShack server would serve it."""
    return web.json_response({"name": "fakeflow", "version": "0.0.0-fake", "auth": {},
                              "apps": [{"id": "icom", "kind": "stationflow", "name": "StationFlow",
                                        "version": "1.24.0-fake", "base": PREFIX + "/", "running": True,
                                        "txCapable": False}]})


async def fake_posts(request):
    return web.json_response({"posts": fake.posts[-500:], "tx_attempts": fake.tx_attempts,
                              "audio_listeners": fake.audio_listeners, "scope_listeners": fake.scope_listeners,
                              "state_sockets": len(fake.state_sockets),
                              "audio_last": fake.audio_last, "scope_last": fake.scope_last})


async def fake_reset(request):
    """Test hook: forget the recorded posts, before a test run."""
    fake.posts.clear()
    fake.tx_attempts.clear()
    return web.json_response({"ok": True})


async def fake_radio(request):
    """Test hook: /fake/radio?connected=0 makes the radio go away (503s, link_state failed)."""
    on = request.query.get("connected", "1") not in ("0", "false")
    fake.radio.update(connected=on, link_state="connected" if on else "failed",
                      error=None if on else "the radio is in use by SHACK-PC")
    await fake.push_state()
    return web.json_response({"ok": True, "connected": on})


async def fake_model(request):
    """Test hook: /fake/model?key=ic-7610 (or ic-705, ic-9700) - a different radio.

    What a client sees when somebody changes the radio in StationFlow's
    settings: the radio drops (switching, link connecting), then comes back as
    the other model on its own band. A display that lays itself out by model
    must follow this without being reopened.
    """
    key = request.query.get("key", "ic-9700").lower()
    if key not in MODELS:
        return web.json_response({"error": f"no such model {key!r}; one of {sorted(MODELS)}"}, status=400)
    fake.radio.update(connected=False, link_state="connecting", ptt=False, power=None, swr=None)
    fake.switching = True
    await fake.push_state()
    await asyncio.sleep(0.6)
    hz, mode, data = MODELS[key]["start"]
    fake.radio.update(model_fields(key))
    fake.radio.update(connected=True, link_state="connected", error=None, freq=hz, mode=mode,
                      data_mode=data, mode_label=mode + ("-D" if data else ""), filter="FIL1",
                      vfo="VFOA", split=False)
    fake.switching = False
    fake.meter()
    await fake.push_state()
    return web.json_response({"ok": True, "model": key})


async def fake_onair(request):
    """Test hook: /fake/onair?on=1 - the radio is transmitting, keyed by something else.

    The web page or WSJT-X keyed it, not the client under test: this is how
    a receive-only client's ON AIR indicator and its PO/SWR meter are seen
    without anything ever asking this fake to transmit. tx_attempts is
    untouched, because nobody attempted anything through the API.
    """
    on = request.query.get("on", "1") not in ("0", "false")
    fake.radio.update(ptt=on, power=(140 if on else None), swr=(1.3 if on else None))
    await fake.push_state()
    return web.json_response({"ok": True, "ptt": on})


async def fake_sstv(request):
    """Test hook: start a picture now."""
    asyncio.ensure_future(receive_picture())
    return web.json_response({"ok": True})


# -- background ---------------------------------------------------------------

async def meter_loop():
    while True:
        await asyncio.sleep(1.0)
        fake.meter()
        await fake.push_state()


async def decode_loop():
    while True:
        now = time.time()
        await asyncio.sleep(15 - now % 15 + 0.5)
        if not fake.digi_holder:
            continue
        rows = fake.make_decodes()
        for ws in list(fake.digi_sockets):
            try:
                await ws.send_json({"decodes": rows})
            except Exception:
                fake.digi_sockets.discard(ws)


_picture_n = 3


async def receive_picture():
    global _picture_n
    _picture_n += 1
    n = _picture_n

    async def tell(msg):
        for ws in list(fake.sstv_sockets):
            try:
                await ws.send_json(msg)
            except Exception:
                fake.sstv_sockets.discard(ws)

    await tell({"start": {"mode": "Robot36", "vis": 8, "width": 320, "height": 256}})
    for step in range(1, 11):
        rows = int(256 * step / 10)
        fake.live_png = fake._picture(n, rows=rows)
        await tell({"line": rows, "of": 256, "progress": round(step / 10, 3)})
        await asyncio.sleep(1.5)
    fake.live_png = None
    rec = fake._add_picture(n)
    await tell({"picture": rec})


async def sstv_loop():
    while True:
        await asyncio.sleep(SSTV_EVERY)
        await receive_picture()


async def background(app):
    tasks = [asyncio.ensure_future(t()) for t in (meter_loop, decode_loop, sstv_loop)]
    fake.make_decodes()   # something on screen at once
    yield
    for t in tasks:
        t.cancel()


def build(ca_pem: bytes | None = None) -> web.Application:
    app = web.Application(middlewares=[auth_mw])
    r = app.router
    for prefix in {PREFIX, ""} if PREFIX else {""}:
        P = prefix
        r.add_get(P + "/healthz", healthz)
        r.add_post(P + "/api/login", login)
        r.add_get(P + "/login", login_page)
        r.add_get(P + "/api/state", state)
        r.add_get(P + "/api/ws", state_ws)
        r.add_post(P + "/api/freq", set_freq)
        r.add_post(P + "/api/mode", set_mode)
        r.add_post(P + "/api/data", set_data)
        r.add_post(P + "/api/filter", set_filter)
        r.add_post(P + "/api/vfo", set_vfo)
        r.add_post(P + "/api/split", set_split)
        for tx in TX_PATHS - {"/api/audio/tx"}:
            r.add_post(P + tx, refuse_tx)
        r.add_get(P + "/api/audio/rx", audio_ws)
        r.add_get(P + "/api/audio", audio_ws)
        r.add_get(P + "/api/scope", scope_ws)
        r.add_get(P + "/api/digi", digi)
        r.add_get(P + "/api/digi/history", digi_history)
        r.add_get(P + "/api/digi/ws", digi_ws)
        r.add_post(P + "/api/digi/mode", digi_mode)
        r.add_post(P + "/api/digi/stop", digi_stop)
        r.add_get(P + "/api/log/spots", spots)
        r.add_get(P + "/api/aprs", aprs)
        r.add_get(P + "/api/aprs/ws", aprs_ws)
        r.add_get(P + "/api/aprs/channels", aprs_channels)
        for a in ("start", "stop", "tune", "mode"):
            r.add_post(P + f"/api/aprs/{a}", ok)
        r.add_get(P + "/api/sstv", sstv)
        r.add_post(P + "/api/sstv/start", sstv_start)
        r.add_get(P + "/api/sstv/pictures", sstv_pictures)
        r.add_get(P + "/api/sstv/picture/{pid}.png", sstv_picture)
        r.add_get(P + "/api/sstv/live.png", sstv_live)
        r.add_get(P + "/api/sstv/ws", sstv_ws)
    r.add_get("/.well-known/openhamshack", well_known)
    r.add_get("/fake/posts", fake_posts)
    r.add_get("/fake/radio", fake_radio)
    r.add_get("/fake/reset", fake_reset)
    r.add_get("/fake/sstv", fake_sstv)
    r.add_get("/fake/model", fake_model)
    r.add_get("/fake/onair", fake_onair)
    app.cleanup_ctx.append(background)
    return app


def make_tls(dirpath: Path) -> tuple[Path, Path, Path]:
    """A CA and a server certificate for 127.0.0.1, 10.0.2.2 (the emulator's host) and localhost."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    dirpath.mkdir(parents=True, exist_ok=True)
    ca_path, cert_path, key_path = dirpath / "ca.crt", dirpath / "server.crt", dirpath / "server.key"
    # Kept across restarts, as a Dock360 host's CA is: a fresh CA on every
    # start would have the app warn "the certificate has changed" each time.
    # Delete the folder to test exactly that.
    if ca_path.exists() and cert_path.exists() and key_path.exists():
        return ca_path, cert_path, key_path
    ca_key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.datetime.now(datetime.timezone.utc)
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "fakeflow Local Authority - ECC Root")])
    ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
          .not_valid_after(now + datetime.timedelta(days=3650))
          .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
          .add_extension(x509.KeyUsage(digital_signature=True, key_cert_sign=True, crl_sign=True, content_commitment=False,
                                       key_encipherment=False, data_encipherment=False, key_agreement=False,
                                       encipher_only=False, decipher_only=False), critical=True)
          .sign(ca_key, hashes.SHA256()))
    key = ec.generate_private_key(ec.SECP256R1())
    sans = [x509.DNSName("localhost")] + [x509.IPAddress(ipaddress.ip_address(a)) for a in ("127.0.0.1", "10.0.2.2")]
    leaf = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "fakeflow")]))
            .issuer_name(ca_name).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=365))
            .add_extension(x509.SubjectAlternativeName(sans), critical=False)
            .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .sign(ca_key, hashes.SHA256()))
    ca_path.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    cert_path.write_bytes(leaf.public_bytes(serialization.Encoding.PEM) + ca.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption()))
    os.chmod(key_path, 0o600)
    return ca_path, cert_path, key_path


async def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default=os.environ.get("FAKEFLOW_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("FAKEFLOW_PORT", "18300")))
    ap.add_argument("--tls", action="store_true", help="also serve HTTPS (port+43) with a CA served on http (port+80)")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    pidfile = Path(os.environ.get("FAKEFLOW_PIDFILE", str(Path.home() / "build" / f"fakeflow-sftv-{args.port}.pid")))
    pidfile.parent.mkdir(parents=True, exist_ok=True)
    pidfile.write_text(str(os.getpid()))

    app = build()
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, args.host, args.port).start()
    log.info("http://%s:%d%s/  password %r", args.host, args.port, PREFIX, PASSWORD)
    if args.tls:
        import ssl
        ca, cert, key = make_tls(Path.home() / "build" / f"fakeflow-sftv-tls-{args.port}")
        ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        ctx.load_cert_chain(cert, key)
        await web.TCPSite(runner, args.host, args.port + 43, ssl_context=ctx).start()
        # The CA, as a Dock360 host serves it on plain HTTP.
        ca_app = web.Application()
        ca_bytes = ca.read_bytes()
        ca_app.router.add_get("/dock360-ca.crt",
                              lambda r: web.Response(body=ca_bytes, content_type="application/x-x509-ca-cert"))
        ca_runner = web.AppRunner(ca_app)
        await ca_runner.setup()
        await web.TCPSite(ca_runner, args.host, args.port + 80).start()
        log.info("https://%s:%d%s/  CA on http://%s:%d/dock360-ca.crt", args.host, args.port + 43, PREFIX,
                 args.host, args.port + 80)
    try:
        await asyncio.Event().wait()
    finally:
        pidfile.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
