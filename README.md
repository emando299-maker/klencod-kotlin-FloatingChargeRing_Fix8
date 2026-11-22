# Floating Charge Ring

Standalone Android app (Kotlin, no extra libraries): a round, draggable floating neon charging ring
showing the battery percentage (with decimals), measured battery power in W and a bolt while charging.

## Use
1. Open the project folder in KlencodIDE as an existing Gradle project, build and install.
2. Open the app, tap the permission button and allow "Display over other apps".
3. Tap "تشغيل النافذة العائمة". Drag the ring with one finger. Use the slider (0-1000 px) to resize.
   0 hides the ring; the ring keeps its position when resized.
4. Stop it from the app or from the notification action.

## Themes
8 colour themes (Themes.kt): pick one in the app; the preview and the floating ring change immediately and the
choice is saved. To add a theme, add one `RingTheme(...)` entry to `Themes.all`.

## Alerts
Charge-complete and low-battery alerts (sound on the alarm stream, vibration, heads-up notification with a
"stop sound" action). Thresholds, sounds (system picker, incl. silent) and vibration are set in the app.
The charge-complete alarm loops (sound + vibration, CPU kept awake) until the charger is unplugged; the low-battery alert plays once.
Alerts are event driven (ACTION_BATTERY_CHANGED) and run while the floating service runs; one alert per
charge/discharge session. The system may deliver battery updates a few seconds late.

## Most used apps
The app screen lists the apps used most (foreground time) over 3 h / 24 h / 7 days, using UsageStatsManager.
This needs "Usage access" (the app opens the right settings page). It is a rough indicator only:
real per-app battery statistics are not available to normal apps, so a button opens the system's own
battery usage screen for the actual numbers.

## Notes
- The W value is battery-side power (|current| x voltage). It is not the charger's rating or USB input power.
- `BatteryReader.kt` is the same reader as in ChargingLiveMeter (see its comments); the app screen shows raw diagnostics.
- Animation pauses when the screen is off.
- This source has not been built or tested on a device in this environment.
