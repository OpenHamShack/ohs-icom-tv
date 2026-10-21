# OpenHamShack Icom for TV

Receive-only Android TV (Fire TV, Google TV) front end for **OpenHamShack
Icom** (called StationFlow until October 2026; this repository, its
packages and the `stationflow` kind keep that name for now), the Dock360
app that runs an Icom IC-9700: listen, tune, meters, band scope
and waterfall, decodes, stations heard, APRS, SSTV pictures. **It never
transmits** - there is no PTT, power or transmit-audio path, hidden or
otherwise, and the client cannot send one.

Built to drop into the one OpenHamShack Remote app as a *kind*
(`~/openhamshack/docs/PLATFORM-PLAN.md`):

| Module | What |
|---|---|
| `android/core-candidates` | product-neutral: addresses with path and https, session cookie and levels, Dock360 CA trust on first use, reconnecting websocket, PCM jitter buffer and AudioTrack player, Bonjour (`_openhamshack._tcp`, Classic and platform adverts), the platform app directory, saved boxes, the TV focus kit. To compare with and fold into openhamshack-remote's `:core`. |
| `android/kind-stationflow` | StationFlow's client and TV screens: `StationFlowKind.open(base, http, title, level)` and `StationFlowTvScreen(session, onExit)`. Takes a base URL and the host's HTTP client; never signs in or discovers. |
| `android/testapp` | a thin host so the kind can be driven on an emulator: a picker, a stand-in for the platform's sign-in, LEANBACK launcher and banner. |
| `tools/fakeflow.py` | a pretend StationFlow speaking the TV API, no radio. |

The contract is `icom/docs/TV-API.md` in dock360-apps (branch
`feature/icom-rx-audio`). Open questions for the server side:
[docs/QUESTIONS.md](docs/QUESTIONS.md). Building, testing and what was
verified: [docs/TESTING.md](docs/TESTING.md). Strings, the string check (it runs with
the unit tests) and frequency formatting: [docs/I18N.md](docs/I18N.md).

## On the TV

The dial has focus from the start. Left/right tunes by the step, OK cycles
the step (10 Hz to 100 kHz; FM starts at 12.5 kHz). Down reaches the chips -
Mode, Filter, Band, VFO, Audio, then Tune…, Decodes…, SSTV… - each OK
changing its value or opening its screen, and below them the latest decodes,
where OK tunes to a station. Up and down are always movement, never a silent
retune; band is also on the remote's channel keys, mute on play/pause, the
Tune panel on Menu. A line at the bottom says what the focused control does.

MIT licence, as StationFlow's.
