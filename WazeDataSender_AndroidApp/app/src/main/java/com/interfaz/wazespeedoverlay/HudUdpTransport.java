package com.interfaz.wazespeedoverlay;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.DhcpInfo;
import android.util.Log;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class HudUdpTransport {
    public interface Listener {
        void onHudStatus(String status);
    }

    private static final byte[] IMAGE_MAGIC = new byte[]{'H', 'U', 'D', 'I'};
    private static final int IMAGE_CHUNK_SIZE = 1180;

    private static HudUdpTransport instance;
    private final Context context;
    private volatile boolean running;
    private volatile InetAddress receiverAddress;
    private volatile int receiverPort = 5062;
    private static final int DISCOVERY_PORT = 5064;
    private volatile Listener listener;
    private volatile long framesSent;
    private volatile long imageFramesSent;
    private volatile long imageChunksSent;
    private volatile long datagramsReceived;
    private volatile long hellosReceived;
    private volatile long beaconsSent;
    private volatile long acksSent;
    private volatile long heartbeatsSent;
    private volatile String lastError = "none";
    private volatile String captureStatus = "not started";
    private DatagramSocket discoverySocket;
    private WifiManager.MulticastLock multicastLock;
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger imageFrameId = new AtomicInteger(1);

    private HudUdpTransport(Context c) {
        context = c.getApplicationContext();
    }

    public static synchronized HudUdpTransport get(Context c) {
        if (instance == null) instance = new HudUdpTransport(c);
        return instance;
    }

    public void setListener(Listener value) {
        listener = value;
        notifyStatus();
    }

    public void setCaptureStatus(String value) {
        captureStatus = value == null ? "unknown" : value;
        notifyStatus();
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("waze-hud-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }

        new Thread(new Runnable() {
            @Override
            public void run() { listen(); }
        }, "HudDiscovery").start();

        new Thread(new Runnable() {
            @Override
            public void run() { heartbeatLoop(); }
        }, "HudHeartbeat").start();
    }

    private void listen() {
        try {
            discoverySocket = new DatagramSocket(null);
            discoverySocket.setReuseAddress(true);
            discoverySocket.setBroadcast(true);
            discoverySocket.setReceiveBufferSize(64 * 1024);
            discoverySocket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), DISCOVERY_PORT));
            discoverySocket.setSoTimeout(2000);
            Log.i("WazeHudUdp", "Sender listening IPv4 0.0.0.0:" + DISCOVERY_PORT);
            notifyStatus();

            byte[] buffer = new byte[4096];

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    discoverySocket.receive(packet);
                    datagramsReceived++;

                    String msg = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8
                    );

                    JSONObject hello = new JSONObject(msg);
                    if (!"HUD_HELLO".equals(hello.optString("type"))) {
                        notifyStatus();
                        continue;
                    }

                    hellosReceived++;
                    receiverAddress = packet.getAddress();

                    int announcedPort = hello.optInt("stream_port", 5062);
                    if (announcedPort > 0 && announcedPort <= 65535) {
                        receiverPort = announcedPort;
                    }

                    Log.i(
                        "WazeHudUdp",
                        "HELLO from " + receiverAddress.getHostAddress() + ":" + receiverPort
                    );

                    sendAck();
                    notifyStatus();
                } catch (SocketTimeoutException ignored) {
                } catch (Exception packetError) {
                    lastError = "packet: " + safeMessage(packetError);
                    Log.e("WazeHudUdp", lastError, packetError);
                    notifyStatus();
                }
            }
        } catch (Exception error) {
            if (running) {
                lastError = "discovery bind: " + safeMessage(error);
                Log.e("WazeHudUdp", lastError, error);
                notifyStatus();
            }
        }
    }

    private void heartbeatLoop() {
        while (running) {
            try {
                sendDiscoveryBeacon("255.255.255.255");

                String subnetBroadcast = getSubnetBroadcast();
                if (subnetBroadcast != null && !"255.255.255.255".equals(subnetBroadcast)) {
                    sendDiscoveryBeacon(subnetBroadcast);
                }

                if (receiverAddress != null) {
                    JSONObject object = new JSONObject();
                    object.put("type", "NAV_SOURCE_STATUS");
                    object.put("protocol", 7);
                    object.put("timestamp", System.currentTimeMillis());
                    object.put("capture_status", captureStatus);
                    object.put("frames_sent", framesSent);
                    object.put("header_images_sent", imageFramesSent);
                    sendJsonNow(object, false);
                    heartbeatsSent++;
                }

                notifyStatus();
                Thread.sleep(1000);
            } catch (Exception error) {
                lastError = "heartbeat: " + safeMessage(error);
                notifyStatus();
            }
        }
    }

    private void sendDiscoveryBeacon(String target) {
        try {
            DatagramSocket socket = discoverySocket;
            if (socket == null || socket.isClosed()) return;

            JSONObject object = new JSONObject();
            object.put("type", "NAV_SOURCE_BEACON");
            object.put("protocol", 7);
            object.put("sender_port", DISCOVERY_PORT);
            object.put("id", android.os.Build.MODEL);
            object.put("timestamp", System.currentTimeMillis());

            byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(
                bytes,
                bytes.length,
                InetAddress.getByName(target),
                5062
            ));
            beaconsSent++;
        } catch (Exception error) {
            lastError = "beacon: " + safeMessage(error);
            notifyStatus();
        }
    }

    private String getSubnetBroadcast() {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wifi == null ? null : wifi.getDhcpInfo();

            if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                byte[] quads = new byte[4];

                for (int i = 0; i < 4; i++) {
                    quads[i] = (byte) ((broadcast >> (i * 8)) & 0xff);
                }

                return InetAddress.getByAddress(quads).getHostAddress();
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private void sendAck() {
        try {
            JSONObject object = new JSONObject();
            object.put("type", "NAV_SOURCE_ACK");
            object.put("protocol", 7);
            object.put("stream_port", receiverPort);
            object.put("sender", "waze-header-stream");
            object.put("timestamp", System.currentTimeMillis());
            sendJsonNow(object, false);
            acksSent++;
        } catch (Exception error) {
            lastError = "ack: " + safeMessage(error);
        }
    }

    public void sendFrame(final JSONObject frame) {
        if (frame == null) return;
        sendExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    frame.put("protocol", 7);
                    sendJsonNow(frame, true);
                    lastError = "none";
                } catch (Exception error) {
                    lastError = "frame: " + safeMessage(error);
                    notifyStatus();
                }
            }
        });
    }

    public void sendHeaderImage(final byte[] jpegBytes, final int imageWidth, final int imageHeight) {
        if (jpegBytes == null || jpegBytes.length == 0) return;

        sendExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    InetAddress address = receiverAddress;
                    if (address == null) return;
                    ensureSendSocket();

                    int frameId = imageFrameId.getAndIncrement();
                    int totalChunks = (jpegBytes.length + IMAGE_CHUNK_SIZE - 1) / IMAGE_CHUNK_SIZE;

                    for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                        int offset = chunkIndex * IMAGE_CHUNK_SIZE;
                        int length = Math.min(IMAGE_CHUNK_SIZE, jpegBytes.length - offset);

                        ByteBuffer packet = ByteBuffer
                            .allocate(18 + length)
                            .order(ByteOrder.BIG_ENDIAN);

                        packet.put(IMAGE_MAGIC);
                        packet.putInt(frameId);
                        packet.putShort((short) chunkIndex);
                        packet.putShort((short) totalChunks);
                        packet.putShort((short) imageWidth);
                        packet.putShort((short) imageHeight);
                        packet.putShort((short) length);
                        packet.put(jpegBytes, offset, length);

                        byte[] bytes = packet.array();
                        discoverySocket.send(new DatagramPacket(
                            bytes,
                            bytes.length,
                            address,
                            receiverPort
                        ));
                        imageChunksSent++;
                    }

                    imageFramesSent++;
                    lastError = "none";
                    notifyStatus();
                } catch (Exception error) {
                    lastError = "header image: " + safeMessage(error);
                    notifyStatus();
                }
            }
        });
    }

    private synchronized void sendJsonNow(JSONObject object, boolean countFrame) throws Exception {
        InetAddress address = receiverAddress;
        if (address == null) throw new IllegalStateException("HUD not discovered");
        ensureSendSocket();
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        discoverySocket.send(new DatagramPacket(bytes, bytes.length, address, receiverPort));
        if (countFrame) framesSent++;
    }

    private void ensureSendSocket() throws Exception {
        if (discoverySocket == null || discoverySocket.isClosed()) {
            discoverySocket = new DatagramSocket();
            discoverySocket.setSendBufferSize(1024 * 1024);
        }
    }

    private void notifyStatus() {
        String address = receiverAddress == null
            ? "not discovered"
            : receiverAddress.getHostAddress() + ":" + receiverPort;

        notifyStatus(
            "HUD receiver: " + address +
            "\nUDP datagrams received: " + datagramsReceived +
            "\nHELLO received: " + hellosReceived +
            "\nDiscovery beacons sent: " + beaconsSent +
            "\nACK sent: " + acksSent +
            "\nHeartbeats sent: " + heartbeatsSent +
            "\nFrames sent: " + framesSent +
            "\nCapture: " + captureStatus +
            "\nLast error: " + lastError +
            "\nListening IPv4 UDP: 0.0.0.0:" + DISCOVERY_PORT +
            "\nBuild: sender v16 single-socket discovery"
        );
    }

    private void notifyStatus(String value) {
        Listener current = listener;
        if (current != null) current.onHudStatus(value);
    }

    private String safeMessage(Exception error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value;
    }
}
