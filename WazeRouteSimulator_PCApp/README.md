# Waze Route Simulator - PC

## Requirements
- Python 3
- Android Platform Tools (`adb`) in PATH
- Phone connected by USB with USB debugging authorized
- Waze Mock Location Bridge installed and opened once
- In Android Developer Options, select **Waze Mock Location Bridge** as the mock-location app

## Start
Run:

```bat
start_server.bat
```

Then open:

```text
http://127.0.0.1:8765
```

The server automatically runs:

```bat
adb forward tcp:8766 tcp:8766
```

## Editor controls
- Click the map to add points.
- Drag a marker to move it.
- Click a segment to select it.
- Ctrl+click toggles additional segments.
- Shift+click selects a continuous segment range.
- Apply a speed to all selected segments.
- Send Route uploads the JSON to the Android app.
- Play, Pause, and Stop control mock-location playback.
