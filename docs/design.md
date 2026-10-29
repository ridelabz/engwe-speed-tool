# Design and screenshots

## Design tokens

The app's look lives in `app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/theme/`.

- **Colours** (`Color.kt`): a fixed palette with a teal accent on neutral surfaces, in a light and a
  dark version. There is no wallpaper-based dynamic colour, so the colours below keep their meaning
  and their contrast on every phone.
  - *Caution* (amber): above the 25 km/h legal limit, and unverified bikes or values.
  - *Confirmed* (green): the bike confirmed a setting.
  - *Error* (red): something failed.
  - *Gauges* (`GaugeColors`): the battery fills green from 50 %, yellow from 20 % and red below; the assist
    bar's segments go from blue (level 1) to magenta (the highest level), level 0 grey. The percentage and
    the level's number are always written out too.

  Content cards use `surfaceContainer`; status cards (errors, can't reach) and the find list's bike rows
  `surfaceContainerHighest`; cards inside sheets `surfaceContainerHigh`. The bike's state is a pill: on
  (green), off or locked (inverse), connecting or not connected (neutral), Bluetooth problems (error).
  Colour never carries meaning on its own: every use also has an icon or text. `ContrastTest` checks
  the text and icon colours against the surfaces and containers they are drawn on (WCAG AA: 4.5:1 for
  text, 3:1 for outlines and for the gauges' fills against the card).
- **Text** (`Type.kt`): five sizes: screen titles and the battery's percentage (28 sp), sheet and dialog
  titles (24 sp), card titles and readings (22 sp), body (16 sp) and small text for labels and notes
  (14 sp). Section titles are `titleMedium` in the text colour. Numbers use tabular figures.
- **Shapes and spacing** (`Shape.kt`): rounded cards (20 dp), sheets (24 dp), a 4–32 dp spacing scale,
  touch targets of at least 48 dp and 56 dp for the speed choices. On wide screens (tablets, landscape)
  a screen's content is at most 600 dp wide, centred.
- **Motion**: screens change with Material's fade-through; colours of the speed card cross-fade. All
  animations follow Android's animation scale ("Remove animations").
- **Top bar**: pinned while the content scrolls, in its own colour (`primaryContainer`, set apart from the
  page; title and icons in `onPrimaryContainer`) on every screen that has one. The dashboard's bar names the
  app ("Speed Tool", one line, read as a heading) and holds the ⋮ menu; other screens' bars hold the back
  arrow, and their titles stay in the content, where long ones wrap.
- **Dashboard**: from top to bottom, the bike (its name with the power button at the end of the row, where
  the bike's profile offers it: a round icon button, filled while the bike is on, outlined while it is off or
  locked; then the state pill and the verification chip), any connection problem as a card of its own, the
  battery as a full-width gauge in 5 cells, the speed limit, the assist level, the lights and the readings.
  Each card has at most one line of status, shown only when there is something to say; its ⓘ opens a small
  sheet with the explanation. The cards keep their place whatever the connection: while the bike isn't
  connected their controls are greyed out and disabled, and the bike's state at the top says why (screen
  readers hear it when it changes).
- **Names**: the home screen shows "Speed Tool" under the icon, and the dashboard's top bar shows the same
  short name; everywhere else the app is "ENGWE Speed Tool (Unofficial)".
- **Icons**: Material Symbols (Rounded, by Google, Apache License 2.0) as vector drawables in
  `res/drawable/ic_*.xml`, only those the app uses.

## Text

All user-visible text lives in string resources (`res/values/strings*.xml`; the legal statements in
`legal.xml`), ready for translation. Lint treats hard-coded text in XML as an error, and
`scripts/check_ui_strings.py` (run in CI) rejects string literals with letters in the app's Kotlin.

## Prototype

Until the app talks to bikes, it runs on a simulated bike (`prototype/PrototypeDriver.kt`) and shows
"DEMO — no real bike" at the top. Test and debug builds have a tab on the right edge that opens a list
of states, so every screen and state can be reviewed without a bike. Nothing in the prototype uses
Bluetooth.

## Screenshots

Screen tests run on the JVM with Robolectric and record screenshots with Roborazzi. Each screen is
captured in light and dark, at 100 % and 200 % font size.

- `./gradlew :app:testDebugUnitTest` composes every screen and runs the UI tests.
- `./gradlew :app:testDebugUnitTest -Pscreenshots` also writes the images to
  `app/build/outputs/roborazzi/`. This needs Robolectric's native graphics, which exists for Linux and
  Windows on x86-64 and for macOS, but not for Linux on ARM.
- CI records them on every run and uploads them as the `screenshots` artifact (kept for 5 days). The
  screenshots are declared outputs of the test task, so a result from the build cache brings them along.
- Robolectric downloads its Android framework jar (`android-all-instrumented`) from Maven Central on
  the first run, into `~/.m2`, outside Gradle's dependency resolution.
