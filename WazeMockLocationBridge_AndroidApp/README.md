# Waze Mock Location Bridge

Open this folder in Android Studio and build/install the `app` module.

After installation:

1. Open Android **Developer Options**.
2. Open **Select mock location app**.
3. Select **Waze Mock Location Bridge**.
4. Open the app once and press **Start bridge service**.
5. Connect USB debugging to the PC.
6. Start `WazeRouteSimulator_PC\start_server.bat`.

The phone HTTP server listens only on `127.0.0.1:8766`. The PC reaches it through:

```bat
adb forward tcp:8766 tcp:8766
```

The app publishes mock fixes through the GPS and network test providers, including:
- coordinates
- speed
- bearing
- accuracy
- wall-clock time
- elapsed realtime


## v2 fix

The manifest now declares `android.permission.ACCESS_MOCK_LOCATION`. Android uses this declaration to list the app under **Developer Options > Select mock location app**.

Uninstall the old APK before installing the rebuilt v2 APK so Android refreshes the eligible-app list.
