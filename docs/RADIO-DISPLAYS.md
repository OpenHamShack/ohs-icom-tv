# The radios' own displays, and how OpenHamShack Icom imitates them

OpenHamShack Icom (was StationFlow) shows the connected
radio as that radio's own screen: on a TV, on a phone or tablet, and in the
module's web page. This document is the one description all of them are
drawn from:

- sections 1-3 say what each radio's screen looks like, from Icom's own
  manuals, with the source of every fact;
- section 4 says what the module's API can and cannot fill in;
- section 5 is the **layout spec**: each radio's screen as rectangles in its
  own pixels. It exists as data in two places, which must agree with it and
  with each other:
  - Android: `android/kind-stationflow/.../radio/Faces.kt`
  - web: `icom/app/static/display.js` (`FACES`), branch `feature/icom-display`
    of `~/dock360-apps`
  - iOS/tvOS (later): to be written from this section.

Change a layout here first, then in both tables.

**Fidelity rules.** Imitate the layout, proportions, colours and readout
style. Do not copy Icom logos, trademarks or bitmap artwork, and ship no
font: system sans-serif, with tabular figures for the frequency. Show only
what the module reports; a region for something it does not report keeps its
place but is empty or says "not reported" (section 4). Nothing on any of these
screens keys the radio: a TX indicator shows that the radio is on the air,
whoever keyed it.

## Sources

All six manuals are Icom's own PDFs from icomjapan.com. Page references
below read "PDF page / printed page".

| Short name | URL |
|---|---|
| 9700-BM (Basic, rev 7) | https://www.icomjapan.com/uploads/support/manual/IC-9700_ENG_Basic_7.pdf (landing page https://www.icomjapan.com/support/manual/2156/) |
| 9700-AM (Advanced, rev 3) | https://www.icomjapan.com/uploads/support/manual/IC-9700_ENG_AM_3.pdf (/support/manual/2166/) |
| 7610-BM (rev 10) | https://www.icomjapan.com/uploads/support/manual/IC-7610_ENG_BM_10.pdf (/support/manual/1711/) |
| 7610-AM (rev 6a) | https://www.icomjapan.com/uploads/support/manual/IC-7610_ENG_AM_6a.pdf (/support/manual/1718/) |
| 705-BM (rev 9) | https://www.icomjapan.com/uploads/support/manual/IC-705_ENG_Basic_9.pdf (/support/manual/3060/) |
| 705-AM (rev 5) | https://www.icomjapan.com/uploads/support/manual/IC-705_ENG_Advanced_5.pdf (/support/manual/3063/) |
| Product pages | https://www.icomjapan.com/lineup/products/IC-7610/ , https://www.icom.co.jp/lineup/products/IC-7610/ , https://www.icomjapan.com/lineup/products/IC-705/ , https://www.icom.co.jp/lineup/products/IC-9700/ |

Reference renders of the relevant pages are kept outside the repo, in
`~/build/icomdisp-ref/` on shack, because they are Icom's artwork:
`ic9700-main.png` (9700-BM cover photo), `ic9700-dualwatch.png`,
`ic9700-single.png`, `ic7610-noscope.png`, `ic7610-scope.png` and
`ic705-main.png`.

**Caveats.**

- Icom states the IC-7610's resolution (800 × 480). It does not state the
  IC-9700's or the IC-705's. 480 × 272 is inferred:
  - the 9700 manual's screen drawings crop to exactly 1.765:1;
  - Icom says the 705's screen is "the same size as the IC-7300 and
    IC-9700";
  - third-party listings give 480 × 272.
- Colours are sampled from manual artwork and the covers' photographs, not
  from a photographed LCD. The scope's colours are the exception: they are
  Icom's own published RGB defaults.
- Proportions measured off small photographs are good to about ±2 %.

## 1. IC-9700 (4.3 in, 480 × 272, inferred)

### 1.1 The screen

- 4.3-inch colour TFT touch LCD (9700-BM p.2, Features).
- Display Type A, a black background, is the default; B is blue
  (9700-AM p.53/7-2; 9700-BM p.74/8-19).
- The frequency font is "Basic" by default, or "Round" (9700-AM p.53/7-2).

### 1.2 Physical keys and on-screen keys

- **Physical keys under the screen:** MENU, FUNCTION, M.SCOPE, QUICK, EXIT
  (9700-BM p.9/1-1; the cover photo).
- **On-screen soft keys** appear only on the scope and decode screens, as a
  row along the bottom. Each is a dark grey gradient with a light grey
  outline and white text.
  - Spectrum scope, menu 1: `< 1 >` SPAN HOLD CENT/FIX EXPD/SET.
  - Spectrum scope, menu 2: `< 2 >` REF SPEED MARKER EXPD/SET
    (9700-BM p.43/5-1).
- The first key, `< n >`, pages through the rows.

### 1.3 Layouts (9700-BM p.21/3-2)

- **Dualwatch on:** MAIN in the upper half, SUB in the lower, both the same
  size. The frequency of the band that is not selected is drawn grey.
- **Dualwatch off:** the MAIN block alone.
  - The mini scope (M.SCOPE) is "displayed only when the Dualwatch function
    is OFF" (p.9/1-1).
- **SPECTRUM SCOPE screen with Dualwatch** (MENU » SCOPE): the two bands are
  short rows at the top and the scope fills the lower half. This is the
  screen on the Basic manual's cover.

This is the screen we imitate: it is the one with the most of the radio on
it, and it is what the 9700 shows on the cover. We draw it without the SUB
block: see section 5, "The sub band: left out for now".

| Region (top to bottom, 9700-BM cover) | y of 272 |
|---|---|
| Status row: TX box at left; clock and MULTI indicator ("PBT1") at right | 0-22 |
| MAIN block: mode button, FIL button, mini S/SWR meter on the left; AGC label; big frequency; "VFO A / 1 / BLANK" on the right | 24-86 |
| SUB block, same layout, grey | 88-150 |
| Scope title bar: `MAIN` `R` `T` `HOLD` `▸▸▸` `SPECTRUM SCOPE` `CENTER` `Grid 100k/10dB` | 152-164 |
| Spectrum (span labels −500k / +500k at the top corners) | 165-196 |
| Waterfall | 196-220 |
| Frequency scale (−400 … +400) | 221-238 |
| Soft keys | 241-271 |

### 1.4 Elements (9700-BM pp.12-13/1-4, 1-5; image `ic9700-dualwatch.png`)

**Top row, left to right:**

- `TX` in a red outline box. It is lit solid red while transmitting, dotted
  out of band, and grey when transmitting is inhibited.
- LMT; DUP−/DUP+/SPLIT/RPS; BK-IN/F-BKIN/VOX.
- M1-M8/T1-T8/COMP in an orange box.
- GPS, LAN (boxed), picture share, SD (blue).
- The clock, large and white.
- The MULTI indicator: a light grey tag ("kHz", "PBT1").

**Each band, line 1:**

- The mode button: a blue gradient rounded rectangle with white text ("FM",
  "USB"; "USB-D" with DATA).
- The filter button: dark grey with a grey border, "FIL1/2/3".
- Then the function icons as plain text: OVF in red, P.AMP/ATT, AN/MN,
  NB/TONE/D-TSQL, NR, EMR, AGC-F/M/S (right-aligned).

**Each band, the frequency:**

- Large white digits in the form `146.520.00`: MHz, kHz and tens of hertz.
- A smaller trailing 1 Hz digit appears only when 1 Hz tuning is switched on
  (9700-BM p.3-4).
- A small white ▼ over a digit is the quick-tuning mark, over the 1 kHz digit
  by default.
- AFC or a `1/4` box sits to the upper right.

**Each band, right column:** VFO A / VFO B / MEMO, then a large memory channel
number, then "★1" or "BLANK".

**Left margin:** RFG in orange when RF gain is reduced; a small green bar
when the squelch is open.

### 1.5 The meter (9700-BM p.30/3-11)

- A horizontal segmented bar under a printed scale.
  - White `S 1 3 5 7 9`, then red `+20 +40 +60dB` over a red baseline.
  - The selected TX meter's scale sits under the bar, e.g. `Po 0 25 50 100%`.
- **S9 sits at 0.50 of the bar**, measured off p.12.
- On the scope screen the meter is squeezed to a mini "S/SWR" bar, labelled
  `1 · 5 · 9` with red dots for the dB marks.
- TX meters: Po, SWR, ALC, COMP, VD, ID. Touching the meter cycles them.
- Peak hold is on by default (p.74/8-19).
- Lit segments: blue (multi-function meter, #005cc8). The 705's cover photo
  shows blue to S9 and red past it, and that is what we draw on all bar
  meters.

### 1.6 The spectrum scope (9700-BM pp.43-45/5-1…5-3; 9700-AM pp.22-24/2-2…2-4)

**Title bar:**

- `MAIN` (or SUB) in a red pill (#f0534b).
- `R`, `T` and `HOLD` dimmed.
- `▸▸▸` in cyan for the sweep speed.
- "SPECTRUM SCOPE" in white.
- The mode in a blue pill: `CENTER`, `FIX`, `SCROLL-C`, `SCROLL-F`.
- `Grid 50k/10dB`.

**Spans and markers:**

- Spans in centre mode are ±2.5, 5, 10, 25, 50, 100, 250 and 500 kHz.
- The ruler under the waterfall reads offsets in kHz from the centre.
- A white centre line marks the operating frequency.

**Default colours** (Icom's own SCOPE SET values, 9700-AM p.24/2-4):

- Current waveform (172,191,191), #ACBFBF, drawn as "Fill".
- Max hold (45,86,115).
- **Waterfall:** colours strongest first are "Red, Yellow, Green,
  Light-blue, Blue, and Black". The noise floor is deep blue.

### 1.7 Colours we use (sampled from the 9700-BM cover photo)

| What | Colour |
|---|---|
| Background | #000000 |
| Mode button gradient | #1a5a8c → #002a4a |
| Filter button / edge | #0a0d0b / #3a3a38 |
| Frequency (selected band) / grey band | #ffffff / #8a8e8a |
| MAIN pill / CENTER pill | #d82a24 / #0a6aa0 |
| Key gradient / edge | #3c403e → #121614 / #4b504d |
| Waterfall floor | deep blue, #00034a … #0a1e9a |

## 2. IC-7610 (7 in, 800 × 480, Icom's figure)

### 2.1 The screen

- 7-inch, "high resolution (800 × 480 pixels)" (icomjapan.com IC-7610
  page; the Japanese page reads "7インチ(800×480ピクセル)"). Aspect 5:3.
- Display Type A, black, is the default. The font is Basic by default
  (7610-AM p.95/91; 7610-BM p.60/8-8).
- **The meter defaults to "Standard", an analogue needle**, on the normal
  screen. Edgewise and Bar are the alternatives (7610-BM p.60/8-8).

### 2.2 Keys

- **Physical keys under the screen:** MENU, M.SCOPE, MP-W, MP-R, AUTO
  TUNE, QUICK, EXIT, REC, PLAY (7610-BM pp.10-11).
- **No FUNCTION key.** Its job is done by the multi-function tiles down the
  left edge.
- **Scope soft keys:**
  - Menu 1: `<MENU1>` SPAN HOLD CENT/FIX MAIN/SUB DUAL EXPD/SET.
  - Menu 2: REF SPEED RBW VBW MARKER EXPD/SET (7610-BM p.44/5-1).

### 2.3 Layout (7610-BM p.13/1-4, p.16/1-7, p.44/5-1)

- **MAIN on the left and SUB on the right, side by side, the same size.**
  The band that is not selected is grey.
- **Left edge:** a full-height column of tiles, ~10 % of the width: ANT 1,
  P.AMP 1, ATT OFF, IP+ OFF, AGC MID, VOX, COMP. Each tile has a thin
  vertical light bar on its right: cyan when on, orange for VOX.

| Region | share of height |
|---|---|
| Status row: ANT, BW, SFT, filter shape, AN/APF; the clock centred ("12:00 10", "UTC"); USB/LAN | 0-8 % |
| Analogue meter, one per band | 8-30 % |
| VFO, blue mode button, FIL2 as plain text; DUAL-W (green) and SPLIT (orange) tags between the bands | 31-39 % |
| Frequency `14.080.000`; the manual's scope page shows `14.180.00` | 39-49 % |
| Memory line (`1 --.---.--`) | 49-53 % |
| Scope title: `M S T HOLD ▸▸▸ RBW VBW SPECTRUM SCOPE CENTER MAIN Grid 10k/10dB` | 54-57 % |
| Spectrum | 57-75 % |
| Waterfall | 75-89 % |
| Scale | 89-91 % |
| Seven soft keys | 92-100 % |

### 2.4 The analogue meter

**Arcs:**

- The S arc: white `S 1 3 5 7 9` and red `+20 +40 +60dB`.
- Inside it: ID, Po (0 50 100 %), SWR (1 1.5 2 3 ∞), COMP (blue), ALC (red)
  and Vd.

**Needle:** white, pivoting below the meter face.

**The arcs span about 120°**, with S9 at about the middle.

### 2.5 Scope colours (7610-AM p.53/49)

- Current waveform: (217,241,247), #D9F1F7.
- Max hold: (58,110,147).
- The waterfall uses the same colour order as the 9700.

## 3. IC-705 (4.3 in, 480 × 272, inferred)

### 3.1 The screen

- 4.3-inch colour TFT touch LCD, "same size as the IC-7300 and IC-9700"
  (icomjapan IC-705 page).
- No Display Type item exists: the screen is black only (705-BM p.70/8-17).

### 3.2 Keys

- **Physical keys under the screen:** MENU, FUNCTION, M.SCOPE, QUICK, EXIT
  (705-BM p.16/2-1).
- **Scope soft keys:** `< 1 >` EDGE HOLD CENT/FIX EXPD/SET.

### 3.3 Layout (705-BM pp.19-20/2-4, 2-5; the cover photo)

One band only; there is no Dualwatch.

| Region | y of 272 |
|---|---|
| Status row: TX, battery, USB, M1/T1, GPS, Bluetooth, WLAN, network, REC, SD, clock, MULTI indicator | 0-30 |
| Mode button and FIL button; P.AMP, MN, NB, NR, AGC-M; RIT and its offset at the right | 33-59 |
| VOX/COMP/SPLIT/OVF/RFG stacked left; the frequency large (`7.070.50`); "VFO A", memory number and BLANK right | 60-114 |
| Meter: S scale over a segmented bar, Po scale under it | 118-150 |
| Scope title (`R` green, `T`, `HOLD`, `▸▸▸`, `SPECTRUM SCOPE`, `FIX`, `Grid 10k/10dB`) | 157-170 |
| Spectrum | 170-203 |
| Waterfall | 203-228 |
| Scale | 228-243 |
| Soft keys | 246-272 |

### 3.4 Meter and scope

- **Meter:** blue segments to S9 and red beyond (the cover photo), with
  `Po 0 25 50 100% (10W)` underneath. **S9 sits at 0.47 of the bar.**
- **Scope:** the default colours are the 9700's (705-AM p.32/3-4).

## 4. What the API provides, and what it does not

From `icom/docs/TV-API.md` (branch `feature/icom-rx-audio`) and the module's
state (`app/radio.py`, `app/registry.py`). Every value below is drawn as
reported. Everything in the second table is **absent** from the screens: its
place is kept, and it is empty or says "not reported". The open questions are
in [QUESTIONS.md](QUESTIONS.md), section "What the radio's own screen shows
that the API does not".

**What the API provides:**

| On the radio's screen | From the API | Drawn |
|---|---|---|
| Main band frequency | `freq` | yes, as `MHz.kHz.10Hz` |
| Mode button | `mode`, `data_mode`, `mode_label` | yes ("USB-D") |
| Filter | `filter` | yes |
| VFO A/B | `vfo` | yes |
| SPLIT | `split` | yes, under the VFO label |
| S meter | `smeter` (raw 0-255), `smeter_db` | yes, on Icom's scale |
| TX box | `ptt` | lit while on the air, whoever keyed it |
| Po / SWR meter | `power` (raw), `swr` | yes, while transmitting |
| Link to the radio | `connected`, `link_state`, `error` | the LAN box, and "NO RADIO" |
| Scope trace and waterfall | `/api/scope` `data`, `start`, `end` | yes |
| Scope mode pill | frame `mode` | CENTER / FIX / SCROLL-C / SCROLL-F |
| MAIN/SUB pill | frame `receiver` | yes |
| Grid width | from the frame's span | width only ("Grid 5k") |
| Band | `bands`, against `freq` | on the BAND key |
| Clock | the device's (TV, phone) or the module's (web) UTC | yes |

**What it does not provide:**

| On the radio's screen | Missing from the API |
|---|---|
| Sub band frequency, mode, filter, meter | everything about the sub receiver (not drawn - section 5) |
| Dualwatch, satellite mode, which band is selected | not reported (not needed while there is no sub band) |
| P.AMP, ATT, AGC, NB, NR, NOTCH, IP+, ANT | not reported |
| TONE/TSQL, DUP±, RIT/ΔTX offset, AFC, 1/4 | not reported |
| VFO/MEMO, memory channel and name | not reported (`vfo` is only A or B) |
| ALC, COMP, VD, ID meters | not reported |
| Scope span setting, reference level, dB per division, sweep speed, markers | span only, as the frame's edges |
| OVF, RFG, VOX, BK-IN, COMP, LMT, M1/T1 | not reported |
| The 7610's tiles (ANT, P.AMP, ATT, IP+, AGC, VOX, COMP) | not reported |

## 5. The layout spec

All numbers are in the radio's own pixels. A view scales the whole face
uniformly to the room it has, so a 480 × 272 face is 16:9 on a 1080p TV and
the 7610's 800 × 480 is 5:3.

Rectangles are written `[x, y, w, h]`. Band-block parts are relative to the
block's top-left. The source of every value is the radio's section above.

**The sub band: left out for now.** The 9700 (Dualwatch) and 7610 show main
and sub together, and the first version of these faces kept the sub's block
on screen, empty, reading "sub band: not reported", because StationFlow
reports only the main receiver. Brian's call on 9 October 2026 was "VFO is
enough, drop sub VFO for now": no face draws a sub block, and the main block
and the scope have its room. This is deliberate, not a gap waiting for
the API. Sections 1-3 keep how each radio draws its sub band, and
QUESTIONS.md 12 keeps what the API would need, for when it is wanted. To
bring it back, give `Face` (Faces.kt) and `FACES` (display.js) a sub block
again and take the room back from the main block and scope as below.

### 5.1 IC-9700

**Status row:** status `[0, 0, 480, 22]`, TX box `[1, 3, 40, 17]`. The clock
is right-aligned at 414, its size 1.35 × text 12.

**Block:** main `[0, 24, 480, 86]`. The radio's SUB block (88-150) is not
drawn; of its 64 px, 24 go to the main block and 40 to the scope. With
Dualwatch the radio drew main 24-86, sub 88-150 and the scope header at
152, its spectrum 31 px and its waterfall 24 px.

**Band block:**

- Mode `[1, 4, 84, 27]` and filter `[91, 4, 54, 27]`, the filter boxed.
- Meter `[4, 40, 141, 24]`, a bar with a 46 px label; S9 at 0.50. The bar
  is under 120 px wide, so it is drawn as the mini meter (1, 5, 9 and dots).
- Frequency right edge 388, baseline 72, size 44 (the radio's 38 with
  Dualwatch), at 10 Hz resolution with no small digits.
- VFO label right-aligned at 479, its top at 26, size 13.
- Dial area `[150, 2, 246, 82]`: where focus is drawn and touch is taken.

**Scope:** header `[0, 114, 480, 12]`, spectrum `[1, 127, 478, 46]`,
waterfall `[1, 173, 478, 47]`, ruler `[1, 221, 478, 17]`.

**Keys:** `[1, 241, 478, 30]`, five to a page with a 1 % gap. The page key
reads `< n >`.

**Trace colour:** #ACBFBF.

### 5.2 IC-705

**Status row:** status `[0, 0, 480, 30]`, TX box `[5, 11, 37, 16]`, clock at
420.

**Block:** main `[0, 31, 480, 122]`. The radio has one receiver, so this
face was never changed for the sub band.

**Band block:**

- Mode `[5, 2, 81, 26]` and filter `[92, 2, 54, 26]`, boxed.
- Meter `[38, 88, 250, 28]`, a bar with a 20 px label; S9 at 0.47.
- Frequency right edge 383, baseline 81, size 58.
- VFO right-aligned at 475, its top at 41, size 13.
- Dial area `[128, 20, 263, 66]`.

**Scope:** header `[3, 157, 474, 13]`, spectrum `[3, 170, 474, 33]`,
waterfall `[3, 203, 474, 25]`, ruler `[3, 228, 474, 15]`.

**Keys:** `[1, 246, 478, 26]`, five to a page. The page key reads `< n >`.

**Trace colour:** #ACBFBF.

### 5.3 IC-7610

**Status row:** status `[80, 0, 720, 34]`. The clock is right-aligned at 484
(centred, as on the radio); text size 15.

**TX box:** `[89, 146, 37, 23]`, just above and left of the VFO line, as on
the radio. It moved down 19 px with the line.

**Block:** main `[96, 36, 704, 214]`, from the tile column to the right
edge. The radio has main `[96, 36, 366, 214]` and sub `[462, 36, 338, 214]`
side by side. Without the sub, the block keeps the radio's order top to
bottom (meter, VFO/mode/filter line, frequency) and takes the whole width
at the same height, because the scope below is unchanged.

**Tile column:** `[0, 0, 80, 440]`, seven tiles named ANT, P.AMP, ATT, IP+,
AGC, VOX, COMP. They carry no values.

**Band block:**

- **Meter:** `[0, 0, 400, 130]`, a needle (the radio's is 288 × 106).
  - Pivot at (centre x, top + 1.38 h); S arc radius 1.30 h.
  - The arcs span 210° to 330°.
  - Po arc at 0.80 r and SWR arc at 0.62 r; S9 at 0.50.
  - The arcs grow with the height, not the width, so the height (fixed by
    the scope below) limits it to 130. It stays at the left: centred over
    the block, its top labels ran into the clock.
- Mode `[46, 138, 83, 31]`.
- Filter `[135, 138, 44, 31]` as plain text, not boxed.
- "VFO A" right-aligned at 42, its top at 144, size 16.
- Frequency right edge 690, baseline 206, size 76. It sits to the right of
  the VFO/mode/filter line rather than under it (the radio's is 56,
  right edge 267, baseline 198). The width the sub left goes here.
- Dial area `[190, 140, 504, 72]`.

**Scope:** header `[95, 263, 692, 16]`, spectrum `[95, 280, 692, 83]`,
waterfall `[95, 363, 692, 66]`, ruler `[95, 429, 692, 15]`.

**Keys:** `[3, 447, 794, 32]`, seven to a page. The page key reads
`<MENUn>`.

**Trace colour:** #D9F1F7.

### 5.4 Phone held upright (compact), and tablets

- **Wide enough for the radio's proportions** (within 8 %): a tablet, a
  phone on its side, a TV. The face is drawn exactly as specified above.
- **Upright**, `compactOf(face, aspect)` applies:
  - The face keeps the radio's width and everything down to the scope
    header unchanged. That is the status row, the bands, the meters and, on
    the 7610, the tile column, cut off at the scope header.
  - The height grows to fill the screen.
  - What is left goes 30 % to the spectrum and 70 % to the waterfall, with
    the ruler under them.
  - Every key is shown at once, in two rows. Paging costs a press on a
    touch screen and buys nothing there.

Touch on phones and tablets:

- drag along the frequency to tune, one step per 22 dp;
- tap the frequency to change the step;
- tap the scope or the waterfall to tune there, to the step's grid;
- tap a key.

### 5.5 Drawing rules common to all three

- **Frequency:** `MHz.kHz.` then the remaining digits to the face's
  resolution, with the last `smallDigits` drawn small. All three are 10 Hz
  with none small, as on the radios' default screens.
  - Bold sans with tabular figures.
  - It shrinks to fit if the device's font is wider than the room between
    the dial area's left edge and the right edge. It never clips.
- **Quick-tuning mark:** a small ▼ over the digit the step moves: the
  digit of the step's leading figure, so 12.5 kHz marks the 10 kHz digit.
- **S meter:** raw 0-120 maps linearly to 0…S9, and 120-241 to S9…+60 dB.
  Unknown is no bar and no needle, never S0.
  - Bar segments are blue to S9 and red beyond.
  - While transmitting, the meter is Po (0-100 % of raw 255), and SWR on
    the web page after a touch.
- **Waterfall:** colours in Icom's order: black → blue → light blue →
  green → yellow → red. Rows not yet swept show the floor colour.
  - The strip shows about two sweeps per radio pixel of its height.
- **Spectrum:** the model's trace colour, filled ("Fill" is Icom's default
  waveform type), over a 10 × 5 grid. A white line marks the operating
  frequency.
  - The top corners read `−span/2` and `+span/2`.
  - The ruler reads kHz offsets in centre mode, and MHz otherwise.
- **Scope title:**
  - `MAIN` in a red pill; `SUB` if the frame says receiver 1 (the Android
    client takes only the main receiver's sweeps, so there it never does).
  - `HOLD`, on the web only: it freezes the page's drawing, not the radio.
  - `SPECTRUM SCOPE`.
  - The mode pill.
  - `Grid <span/10>`.
- **Keys:** the radio's own look (a grey gradient, a light edge, white
  text). Each key carries a second line showing its current value.
  - The actions are receive-side only:
    - TV and phone: MODE, FIL, BAND, A/B, F-INP (the tune panel), DECODE,
      SSTV, MUTE.
    - Web: MODE, FIL, BAND, A/B, F-INP (keypad), SPLIT, HOLD.
  - None transmits. `KeyAction` on Android has no member that could, and
    a unit test holds it.
- **Absent things:**
  - the 7610's tiles show their names only;
  - the sub band is not drawn at all (see the top of this section). Until
    9 October 2026 it was an outlined, empty block in the "absent" grey
    (#45494a) reading "sub band: not reported".
- **Focus (TV only):** a light blue ring (#4fc3f7) round the dial area or
  the key. While the dial has focus, the ▼ is drawn in the same blue. The
  radio has no focus ring, but a TV must show one.
- **Not connected:** a red-edged box over the spectrum reads "NO RADIO",
  "Not answering", or the module's own reason. The meter goes blank.
