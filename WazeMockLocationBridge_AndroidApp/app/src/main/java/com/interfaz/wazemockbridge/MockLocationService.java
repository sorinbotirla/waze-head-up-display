package com.interfaz.wazemockbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MockLocationService extends Service {
    private static final int PORT = 8766;
    private static final String CHANNEL_ID = "mock_location_bridge";
    private static final int NOTIFICATION_ID = 4201;

    private final Object lock = new Object();
    private final List<RoutePoint> points = new ArrayList<>();
    private final List<RouteSegment> segments = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile boolean playing = false;
    private volatile boolean paused = false;
    private volatile int updateHz = 8;
    private volatile int currentSegment = 0;
    private volatile double segmentProgressMeters = 0.0;
    private volatile Location lastInjected = null;
    private volatile String lastError = "";
    private volatile ServerSocket serverSocket;

    private LocationManager locationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for route"));
        setupProviders();
        startHttpServer();
        startPlaybackLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        playing = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Mock location playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Waze Mock Location Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void setupProviders() {
        setupProvider(LocationManager.GPS_PROVIDER);
        setupProvider(LocationManager.NETWORK_PROVIDER);
    }

    private void setupProvider(String provider) {
        try {
            locationManager.removeTestProvider(provider);
        } catch (Exception ignored) {
        }

        try {
            locationManager.addTestProvider(
                    provider,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
            );
            locationManager.setTestProviderEnabled(provider, true);
        } catch (SecurityException e) {
            lastError = "Select this app as the mock-location app in Developer Options.";
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void startHttpServer() {
        running.set(true);
        Thread thread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT, 20, InetAddress.getByName("127.0.0.1"));
                while (running.get()) {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> handleClient(socket), "http-client").start();
                }
            } catch (Exception e) {
                if (running.get()) {
                    lastError = "HTTP server: " + e.getMessage();
                }
            }
        }, "mock-http-server");
        thread.start();
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if ("Content-Length".equalsIgnoreCase(name)) {
                        contentLength = Integer.parseInt(value);
                    }
                }
            }

            char[] bodyChars = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = reader.read(bodyChars, read, contentLength - read);
                if (r < 0) {
                    break;
                }
                read += r;
            }
            String body = new String(bodyChars, 0, read);

            String[] first = requestLine.split(" ");
            String method = first.length > 0 ? first[0] : "GET";
            String path = first.length > 1 ? first[1] : "/";

            JSONObject response;
            int code = 200;

            if ("GET".equals(method) && "/status".equals(path)) {
                response = statusJson();
            } else if ("POST".equals(method) && "/route".equals(path)) {
                response = loadRoute(new JSONObject(body.isEmpty() ? "{}" : body));
                if (!response.optBoolean("ok")) {
                    code = 400;
                }
            } else if ("POST".equals(method) && "/play".equals(path)) {
                response = play();
            } else if ("POST".equals(method) && "/pause".equals(path)) {
                paused = !paused;
                response = statusJson();
            } else if ("POST".equals(method) && "/stop".equals(path)) {
                response = stopPlayback();
            } else if ("POST".equals(method) && "/seek".equals(path)) {
                JSONObject request = new JSONObject(body.isEmpty() ? "{}" : body);
                currentSegment = Math.max(0, request.optInt("segment", 0));
                segmentProgressMeters = Math.max(0.0, request.optDouble("progressMeters", 0.0));
                response = statusJson();
            } else {
                code = 404;
                response = new JSONObject().put("ok", false).put("error", "Unknown path");
            }

            writeJson(s.getOutputStream(), code, response);
        } catch (Exception e) {
            lastError = "HTTP request: " + e.getMessage();
        }
    }

    private void writeJson(OutputStream out, int code, JSONObject object) throws Exception {
        byte[] body = object.toString().getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 400 ? "Bad Request" : "Not Found";
        String headers =
                "HTTP/1.1 " + code + " " + status + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private JSONObject loadRoute(JSONObject route) throws Exception {
        JSONArray jsonPoints = route.optJSONArray("points");
        JSONArray jsonSegments = route.optJSONArray("segments");
        if (jsonPoints == null || jsonPoints.length() < 2) {
            return new JSONObject().put("ok", false).put("error", "At least two points are required.");
        }

        synchronized (lock) {
            points.clear();
            segments.clear();

            for (int i = 0; i < jsonPoints.length(); i++) {
                JSONObject p = jsonPoints.getJSONObject(i);
                points.add(new RoutePoint(p.getDouble("lat"), p.getDouble("lon")));
            }

            if (jsonSegments != null) {
                for (int i = 0; i < jsonSegments.length(); i++) {
                    JSONObject seg = jsonSegments.getJSONObject(i);
                    segments.add(new RouteSegment(
                            seg.optInt("from", i),
                            seg.optInt("to", i + 1),
                            Math.max(1.0, seg.optDouble("speedKmh", 30.0))
                    ));
                }
            }

            while (segments.size() < points.size() - 1) {
                int i = segments.size();
                segments.add(new RouteSegment(i, i + 1, 30.0));
            }

            updateHz = Math.max(1, Math.min(20, route.optInt("updateHz", 8)));
            currentSegment = 0;
            segmentProgressMeters = 0.0;
            playing = false;
            paused = false;
            lastError = "";
        }

        updateNotification("Route loaded: " + points.size() + " points");
        return statusJson();
    }

    private JSONObject play() throws Exception {
        synchronized (lock) {
            if (points.size() < 2 || segments.isEmpty()) {
                return new JSONObject().put("ok", false).put("error", "No route loaded.");
            }
            if (currentSegment >= segments.size()) {
                currentSegment = 0;
                segmentProgressMeters = 0.0;
            }
            playing = true;
            paused = false;
        }
        return statusJson();
    }

    private JSONObject stopPlayback() throws Exception {
        playing = false;
        paused = false;
        currentSegment = 0;
        segmentProgressMeters = 0.0;
        updateNotification("Stopped");
        return statusJson();
    }

    private void startPlaybackLoop() {
        Thread thread = new Thread(() -> {
            long previousNanos = SystemClock.elapsedRealtimeNanos();

            while (running.get()) {
                long now = SystemClock.elapsedRealtimeNanos();
                double dt = Math.max(0.0, (now - previousNanos) / 1_000_000_000.0);
                previousNanos = now;

                try {
                    if (playing && !paused) {
                        tick(dt);
                    }
                } catch (Exception e) {
                    lastError = "Playback: " + e.getMessage();
                    playing = false;
                }

                try {
                    Thread.sleep(Math.max(20, 1000 / Math.max(1, updateHz)));
                } catch (InterruptedException ignored) {
                }
            }
        }, "mock-playback");
        thread.start();
    }

    private void tick(double dt) throws Exception {
        RoutePoint from;
        RoutePoint to;
        RouteSegment segment;

        synchronized (lock) {
            if (currentSegment >= segments.size()) {
                playing = false;
                updateNotification("Route complete");
                return;
            }

            segment = segments.get(currentSegment);
            from = points.get(segment.from);
            to = points.get(segment.to);
        }

        double segmentLength = haversineMeters(from.lat, from.lon, to.lat, to.lon);
        double speedMps = segment.speedKmh / 3.6;
        segmentProgressMeters += speedMps * dt;

        while (segmentProgressMeters >= segmentLength && segmentLength > 0.01) {
            segmentProgressMeters -= segmentLength;
            currentSegment++;
            synchronized (lock) {
                if (currentSegment >= segments.size()) {
                    inject(to.lat, to.lon, 0f, (float) bearingDegrees(from.lat, from.lon, to.lat, to.lon));
                    playing = false;
                    updateNotification("Route complete");
                    return;
                }
                segment = segments.get(currentSegment);
                from = points.get(segment.from);
                to = points.get(segment.to);
            }
            segmentLength = haversineMeters(from.lat, from.lon, to.lat, to.lon);
            speedMps = segment.speedKmh / 3.6;
        }

        double fraction = segmentLength <= 0.01 ? 1.0 : Math.min(1.0, segmentProgressMeters / segmentLength);
        RoutePoint interpolated = interpolate(from, to, fraction);
        float bearing = (float) bearingDegrees(from.lat, from.lon, to.lat, to.lon);
        inject(interpolated.lat, interpolated.lon, (float) speedMps, bearing);

        updateNotification(String.format(
                Locale.US,
                "Segment %d/%d · %.0f km/h",
                currentSegment + 1,
                segments.size(),
                segment.speedKmh
        ));
    }

    private void inject(double lat, double lon, float speedMps, float bearing) {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(lat);
        location.setLongitude(lon);
        location.setAltitude(70.0);
        location.setAccuracy(3.0f);
        location.setSpeed(speedMps);
        location.setBearing(bearing);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

        try {
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
        } catch (Exception e) {
            setupProvider(LocationManager.GPS_PROVIDER);
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
        }

        Location network = new Location(location);
        network.setProvider(LocationManager.NETWORK_PROVIDER);
        network.setAccuracy(5.0f);
        try {
            locationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, network);
        } catch (Exception ignored) {
        }

        lastInjected = location;
    }

    private JSONObject statusJson() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("ok", true);
        obj.put("playing", playing);
        obj.put("paused", paused);
        obj.put("routePoints", points.size());
        obj.put("routeSegments", segments.size());
        obj.put("currentSegment", currentSegment);
        obj.put("segmentProgressMeters", segmentProgressMeters);
        obj.put("updateHz", updateHz);
        obj.put("lastError", lastError);

        if (lastInjected != null) {
            JSONObject loc = new JSONObject();
            loc.put("lat", lastInjected.getLatitude());
            loc.put("lon", lastInjected.getLongitude());
            loc.put("speedKmh", lastInjected.getSpeed() * 3.6);
            loc.put("bearing", lastInjected.getBearing());
            loc.put("accuracy", lastInjected.getAccuracy());
            obj.put("location", loc);
        } else {
            obj.put("location", JSONObject.NULL);
        }

        return obj;
    }

    private static RoutePoint interpolate(RoutePoint a, RoutePoint b, double fraction) {
        return new RoutePoint(
                a.lat + (b.lat - a.lat) * fraction,
                a.lon + (b.lon - a.lon) * fraction
        );
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2)
                * Math.sin(dl / 2) * Math.sin(dl / 2);
        return r * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2)
                - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    private static final class RoutePoint {
        final double lat;
        final double lon;

        RoutePoint(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final class RouteSegment {
        final int from;
        final int to;
        final double speedKmh;

        RouteSegment(int from, int to, double speedKmh) {
            this.from = from;
            this.to = to;
            this.speedKmh = speedKmh;
        }
    }
}
