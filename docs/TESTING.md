# Building and testing StationFlow TV

## Build

Toolchain as OpenHamShack Remote's (`~/android-toolchain`: JDK 17, SDK, AVDs).

```sh
export JAVA_HOME=~/android-toolchain/jdk17 ANDROID_HOME=~/android-toolchain/sdk
cd ~/stationflow-tv/android
./gradlew assembleDebug testDebugUnitTest
```

APKs: `android/testapp/build/outputs/apk/debug/testapp-<abi>-debug.apk` -
`armeabi-v7a` (Fire TV), `arm64-v8a` (Google TV, phones), `x86` (the TV
emulator), plus `universal`. Nothing is native, so the APKs differ only in
name; one per ABI keeps "which file" a non-question.

## Unit tests

- `core-candidates`: addresses (Dock360 prefixes, https), cookies and levels,
  the jitter buffer, PCM decoding, the tone probe, Bonjour TXT (both advert
  shapes), the platform directory, saved boxes, trust on first use against a
  MockWebServer with its own CA.
- `kind-stationflow`: the contract's example state and 1.23.0's, decodes,
  spots, APRS and SSTV records (fixtures are fakeflow's answers, in
  `src/test/resources`), the tuning arithmetic, the waterfall, and the
  client against a recording MockWebServer - including that **no transmit
  path can be sent** (each throws before a byte leaves).
  Also the copied ShackFormat against the shared i18n vectors, and the
  Heard list's frequency column.
- Before any of them, `i18nCheck` runs `python3 tools/i18n.py check` (needs
  python3 3.11+) and fails the build on a user-visible literal outside the
  catalogs: see [I18N.md](I18N.md).

## fakeflow

```sh
python3 tools/fakeflow.py --tls        # http :18300, https :18343, CA on http :18380
```

StationFlow's API as `TV-API.md` describes it, with arithmetic for a radio:
see the docstring. Password `fakeflow` (`FAKEFLOW_PASSWORD`; empty for an open
module). Pid file `~/build/fakeflow-sftv-<port>.pid`; stop it with
`kill $(cat ~/build/fakeflow-sftv-18300.pid)`, never `pkill -f`.
`FAKEFLOW_SSTV_EVERY=100000` stops pictures arriving on their own while you
drive the remote.

Test hooks (open): `/fake/posts` (every POST, and every transmit path asked
for - must stay empty), `/fake/reset`, `/fake/radio?connected=0|1`,
`/fake/sstv` (a picture now), `/fake/model?key=ic-7610|ic-705|ic-9700`
(switch radio, as a settings change in the module would: the link drops
for 0.6 s and returns as that model on its own band), `/fake/onair?on=1|0`
(the radio shown transmitting, as though the web page had keyed it - for
seeing TX indicators; nothing is recorded in `tx_attempts` because the
client asked for nothing). The CA is kept in
`~/build/fakeflow-sftv-tls-18300/`; delete it to test a changed certificate.

## Emulator

AVD `ohsr-tv` (Android TV, API 30, x86). Another agent uses `ohsr` on 5600; this
one ran on 5620, read-only so the AVD is untouched:

```sh
export ANDROID_HOME=~/android-toolchain/sdk ANDROID_AVD_HOME=~/android-toolchain/avd
$ANDROID_HOME/emulator/emulator -avd ohsr-tv -port 5620 -read-only -no-window -no-audio \
    -gpu swiftshader_indirect -no-boot-anim -no-snapshot &
echo $! > ~/build/emulator-sftv-5620.pid
adb -s emulator-5620 install -r android/testapp/build/outputs/apk/debug/testapp-x86-debug.apk
adb -s emulator-5620 shell am start -n com.openhamshack.stationflow.testapp/.MainActivity --ei caPort 18380
```

Always `adb -s emulator-5620`: the Fire TV (198.51.100.45) is on the same adb.
In the app, add `https://10.0.2.2:18343/icom/` (the emulator's name for the
host), trust the certificate - compare the fingerprint with
`openssl x509 -in ~/build/fakeflow-sftv-tls-18300/ca.crt -noout -fingerprint -sha256` -
and sign in with `fakeflow`. `--ei caPort 18380` is the debug-only way to
fetch fakeflow's CA: an emulator cannot reach its host's port 80 (the real
Dock360) nor bind its own.

The emulator has no speaker. `adb logcat -s AudioProbe` (debug builds) prints
the received audio's strongest frequency every 3 s; against fakeflow it must
say `peak=1000Hz rms=0.224`.

Bonjour on the emulator: publish pretend adverts from the device itself
(they advertise the emulator's own address, so opening one reaches nothing):

```sh
adb -s emulator-5620 shell am broadcast -n com.openhamshack.stationflow.testapp/.DebugAdvertiser \
    --es name GM4BRI --ei port 80 --es platform 1 --es kinds datv,stationflow
```

## What was verified (9 October 2026, emulator, D-pad only)

Against fakeflow speaking the final TV-API.md:

- Picker: typed `https://` address; untrusted CA prompt with the right
  fingerprint (matched openssl), focus on Cancel; after the CA changed, the
  pin refused and "The certificate has changed" asked again; password
  prompt, "Wrong password" shown above the field, right password signs in;
  a session from before a server restart reused without asking.
- Main screen: state over `wss://` with the pinned CA, S-meter in S-units,
  scope and waterfall, decodes strip, hint line per focused control.
- Audio: 1000 Hz received and written to AudioTrack (AudioProbe), ~110 ms
  buffered, no underruns over minutes, played rate converging on 12000/s;
  ack flow control on; a client that does not ack is held to 500 ms
  (fakeflow).
- Tuning: left/right by the step with grid snapping (each press one
  `/api/freq`, server state matched), OK cycles the step, Mode/Filter/Band/
  VFO chips, Tune panel (typed 145.5, mode from the full list, Back closes),
  the waterfall shifting with the dial and clearing on a band change.
- Heard: decodes, stations, APRS; OK on a decode heard at 144.174 put the
  radio on 144.174.000 USB-D from 145.000 AM.
- SSTV: gallery with the picture coming in (60%), finished picture first;
  full screen when the remote was idle, a banner instead when it was in use.
- Mute (play/pause) silences without dropping the stream; Home closes
  every socket at the server (audio, scope, state 1/1/1 to 0/0/0) and
  returning reopens them; radio gone shows the module's reason and a tune
  says the radio is not connected; server killed shows "Not answering" and
  everything came back by itself when it returned.
- Bonjour: a platform advert and a single-app advert listed, a `kind=datv`
  one filtered out.
- `/fake/posts` `tx_attempts` stayed empty throughout.

Not verified: the real StationFlow (the module stays stopped), the real
radio, a real Fire TV or Google TV (another agent is using the Fire TV),
opening a platform server through its directory end to end (unit-tested and
served by fakeflow, but an emulator's Bonjour cannot point at the host),
the View level on a device (the test host always passes Control), real
speaker output.

## The radio-shaped display (9 October 2026)

`docs/RADIO-DISPLAYS.md` is the spec. To see it:

```sh
FAKEFLOW_PORT=18320 FAKEFLOW_PASSWORD= python3 tools/fakeflow.py --tls &   # plain http on 18320, no password
adb -s emulator-5620 shell am start -n com.openhamshack.stationflow.testapp/.MainActivity \
    --es open http://10.0.2.2:18320/icom/                                 # debug builds: opens it directly
curl -s localhost:18320/fake/model?key=ic-7610                            # or ic-705, ic-9700
curl -s "localhost:18320/fake/onair?on=1"                                 # the TX box and Po meter
```

The phone and tablet layouts run on AVD `ohsr` (here on port 5622,
read-only). A tablet is that phone with
`adb shell wm size 2560x1600; adb shell wm density 320`, and
`wm size reset; wm density reset` afterwards. The web page's faces are in
`icom/tools/display_preview.html` (headless: `chromium --headless=new
--allow-file-access-from-files --screenshot=… "file://…/display_preview.html?model=ic-7610"`)
and are checked by `icom/tools/panel_window_test.html`.

Verified on the emulators against fakeflow:

- TV (`ohsr-tv`, 1080p, D-pad only):
  - the 9700, 7610 and 705 faces, switched live by `/fake/model`;
  - focus on the frequency at open and again after a panel closes;
  - left/right tunes, with each press one `/api/freq`;
  - Down lands on MODE;
  - the page key pages the bar;
  - F-INP opens the Tune panel and Back closes it;
  - the TX box and the Po needle while `/fake/onair` is on, with
    `tx_attempts` empty throughout.
- Phone (`ohsr`, upright): the compact 9700 face. A drag on the frequency
  tuned four steps; a tap on MODE and BAND changed them.
- Tablet (`ohsr` at 2560×1600): the 7610 face landscape, and compact upright.

Re-checked the same day after the sub band was dropped (RADIO-DISPLAYS.md
section 5): all three faces on `ohsr-tv`, the 9700 upright on `ohsr`, the
7610 on the tablet both ways, and the web faces in the preview page. On
the 7610, D-pad right still tunes (two `/api/freq`), and with `/fake/onair`
on, the TX box sits beside the moved VFO line. `tx_attempts` stayed empty.

Not verified: the real radio, a real TV, phone or tablet, and the web
page inside the running module (only its faces, in the harness and the
preview page).
