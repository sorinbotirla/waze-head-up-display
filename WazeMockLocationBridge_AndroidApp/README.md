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
