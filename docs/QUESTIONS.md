# Questions for the StationFlow server side

Raised while building the receive-only TV kind against `icom/docs/TV-API.md`
(final version, branch `feature/icom-rx-audio`, at `0c091a8`). Each says what
the app does meanwhile, so nothing here blocks it.

Settled while this was being written, and kept so nobody asks again:

- *S-meter units.* The first draft said `smeter` was 0-1; the final says raw
  0-255 (241 = S9+60). The app reads raw, bar = raw/241.
- *App sign-in.* JSON login, levels and `api/auth` were withdrawn; the host
  signs in and passes the kind a client and a level.

## Open

1. **Levels before the platform.** Until `forward_auth` exists, the only
   sign-in is the web page's `ACCESS_PASSWORD` form login, whose cookie is a
   full session: the module would accept a PTT from it. The TV refuses to
   send one (an allow-list in `StationFlowClient`, unit-tested), but nothing
   on the server stops a TV's cookie being used to transmit. When levels land,
   how does an app learn its own level - echoed in `api/state` (an `auth`
   block), a response header, or only from the platform's sign-in? *Meanwhile:*
   the kind takes the level from the host (`StationFlowKind.open(level=...)`)
   and reads an `auth` block from the state if one ever appears.

2. **Which receiver's scope.** Frames carry `receiver` 0 or 1. Will
   `/api/scope` ever send both on one socket (dual watch)? Can a client ask
   for one (`?receiver=1`)? *Meanwhile:* only receiver 0 is drawn; all frames
   are acked. **Deferred, not needed now** (9 October 2026): with no sub band
   on any display (question 12), only the main receiver's scope is wanted.

3. **Scope ack timing.** The app acks `seq` when the sweep is in its
   waterfall model, not when the screen has drawn it, so a scope hidden under
   a panel does not stall the socket. Is that within the intent ("after
   drawing a frame")?

4. **APRS station frequency.** Station records in `api/aprs` have no
   `freq_hz` (packets do). To tune to a station the app uses the first entry
   of `api/aprs/channels` - whose response shape is not in the contract; the
   app looks for `channels[].hz` or `freq_hz`. Could a station carry the
   frequency it was last heard on, and could `api/aprs/channels` be
   documented (and say which channel is this region's)?

5. **Spot dial frequency.** A spot's `freq_hz` is `rf_hz or dial_hz`
   (`app/spots.py`). The app computes the dial as `freq_hz - audio_hz`, which
   is wrong whenever `freq_hz` fell back to `dial_hz`. Could spots carry
   `dial_hz` explicitly (as decodes do)?

6. **DX cluster spots.** The TV brief asks for "DX spots"; the contract has
   only decode-derived stations (`api/log/spots`). Is a DX cluster feed (call,
   frequency, mode, spotter, comment) planned? *Meanwhile:* "Stations heard"
   shows `api/log/spots`.

7. **SSTV picture in flight.** The app polls `api/sstv/live.png` every 2 s
   while a picture comes in, rather than drawing `rows` (base64 RGB24), because
   the picture's width and height are not documented in `start`/`header`.
   Is polling at 0.5 Hz from a few TVs acceptable? If the TV should draw rows,
   which message carries the picture's dimensions?

8. **SSTV and decoders not running.** The TV offers "Start FT8/FT4/JS8
   decoding", "Start the SSTV receiver" and "Start the APRS receiver"
   (`control` routes) when nothing is running, and never sends `"force"` on a
   409. Is a TV starting decoders welcome, or should a TV only ever show what
   the page started?

9. **Dock360's LAN certificate.** The app trusts Dock360's CA on first use
   (fetched from `http://<host>/dock360-ca.crt`, fingerprint shown, pinned per
   box), and keeps hostname verification. Does Caddy's internal certificate
   for the published route carry the LAN IP address as a SAN, so
   `https://198.51.100.208/icom/` verifies? (fakeflow's does; the real one has
   not been tried.) And will the platform edition keep this CA, or change how
   certificates work (PLATFORM-PLAN says that is part of its design)?

10. **The platform directory.** For `/.well-known/openhamshack`, is `base` a path
    on the same server (`/icom/`) or a full URL, and does an app with
    `running: false` appear at all? The app accepts both forms of `base` and
    prefers a running StationFlow.

11. **`window_ms` for a TV.** The app asks for the default 500 ms and adds a
    150 ms jitter buffer. Would a smaller window (say 250 ms) be a problem for
    the module if TVs used it for lower latency?

## What the radio's own screen shows that the API does not

Raised while building the radio-shaped displays (`docs/RADIO-DISPLAYS.md`,
section 4 has the full table). Until each is answered, the screens keep its
place and leave it empty or say "not reported"; none is guessed.

12. **The sub receiver. Deferred, not needed now.** Brian, 9 October 2026:
    "VFO is enough, drop sub VFO for now". The displays no longer draw a sub
    block at all (RADIO-DISPLAYS.md section 5), so nothing waits on this,
    and Dualwatch, satellite mode and band selection are not wanted either.
    The question is kept for when the sub receiver is wanted. The IC-9700 and IC-7610 show main and sub at once
    and the 9700's default screen is Dualwatch. The state has one `freq`,
    `mode`, `filter` and `smeter`, which are the main receiver's (the module
    note on the 7610 says CI-V reaches the sub as a separate selection). Could
    the state carry a `sub` block with the same fields - and `dualwatch`,
    `satellite`, and which band is selected - read with the 0x07 0xD0/0xD1
    selection the radio already offers? Until 9 October 2026 the sub block
    was drawn empty, reading "sub band: not reported". Now it is not drawn.

13. **Receive settings on the band line.** P.AMP/ATT, AGC (F/M/S), NB, NR,
    NOTCH, IP+ and (7610) ANT are all CI-V readable (0x16 sub-commands, 0x11,
    0x12). Reading them every 250 ms is cheap next to the meter. Worth adding
    to the state, read-only, so a display can show them as the radio does?
    A TV would not set them.

14. **FM repeater state.** DUP+/DUP−, the offset, TONE/TSQL/DTCS and the
    tone frequency are what a 2 m/70 cm operator looks at on the 9700's top
    row. Not in the state.

15. **VFO or memory.** `vfo` is only `VFOA`/`VFOB`. On a memory channel the
    radio shows MEMO, the channel number and its name. Could the state say
    `"MEMO"` with `memory: {number, name}`?

16. **RIT/ΔTX.** On/off and the offset are on every band line. Not in the
    state.

17. **The other meters.** Only S, Po and SWR are reported; the radios also
    meter ALC, COMP, VD and ID (and the 9700 shows the TX meter scale under
    the S scale while receiving). Worth reading ALC at least, since it is
    what an operator watches while transmitting data modes.

18. **The scope's settings.** Frames carry their edges, so the span is
    known, but not the reference level, the dB per division ("Grid
    100k/10dB" is drawn as "Grid 100k"), the sweep speed, or the scope's
    hold. Could the opening scope message (or each frame) carry `ref_db`,
    `speed` and `hold`?

19. **Display resolution.** The radios show 10 Hz by default and 1 Hz when
    the operator asks. Does the module know which (CI-V 0x1A 0x05 …)? The
    display draws 10 Hz, as the default screens do.
