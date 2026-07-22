package com.interfaz.hudnavigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class HudUdpReceiver {
    private static final byte[] IMAGE_MAGIC = new byte[]{'H', 'U', 'D', 'I'};

    private final Context context;
    private final HudView view;
    private volatile boolean running;
    private volatile long helloCount;
    private volatile long beaconCount;
    private volatile long unicastHelloCount;
    private volatile long receivedCount;
    private volatile long ackCount;
    private volatile long statusCount;
    private volatile long frameCount;
    private volatile long imageFrameCount;
    private volatile long imageChunkCount;
    private volatile String localIp = "unknown";
    private volatile String broadcastIp = "unknown";
    private volatile String senderIp = "not discovered";
    private volatile String senderStatus = "none";
    private volatile String lastError = "none";
    private DatagramSocket receiveSocket;
    private WifiManager.MulticastLock multicastLock;
    private final Map<Integer, ImageAssembly> assemblies = new HashMap<Integer, ImageAssembly>();

    public HudUdpReceiver(Context c, HudView v) {
        context = c.getApplicationContext();
        view = v;
    }

    public void start() {
        if (running) return;
        running = true;
        localIp = findLocalIpv4();
        broadcastIp = findBroadcastAddress();
        updateDiagnostics();

        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("hud-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }

        new Thread(new Runnable() {
            @Override
            public void run() { receiveLoop(); }
        }, "HudReceive").start();

        new Thread(new Runnable() {
            @Override
            public void run() { helloLoop(); }
        }, "HudHello").start();
    }

    public void stop() {
        running = false;
        if (receiveSocket != null) receiveSocket.close();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    private void helloLoop() {
        while (running) {
            sendHello("255.255.255.255");
            if (!"unknown".equals(broadcastIp) && !"255.255.255.255".equals(broadcastIp)) sendHello(broadcastIp);
            helloCount++;
            updateDiagnostics();
            try { Thread.sleep(1000); } catch (Exception ignored) { }
        }
    }

    private void sendHello(String target) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            JSONObject object = new JSONObject();
            object.put("type", "HUD_HELLO");
            object.put("id", android.os.Build.MODEL);
            object.put("stream_port", 5062);
            object.put("protocol", 7);
            object.put("local_ip", localIp);
            byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
            // Current sender listens on 5064. Also send to legacy 5060 so older
            // sender builds remain discoverable.
            InetAddress address = InetAddress.getByName(target);
            socket.send(new DatagramPacket(bytes, bytes.length, address, 5064));
            socket.send(new DatagramPacket(bytes, bytes.length, address, 5060));
        } catch (Exception error) {
            lastError = "hello: " + safeMessage(error);
        } finally {
            if (socket != null) socket.close();
        }
    }

    private void sendHelloToSender(InetAddress senderAddress, int senderPort) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();

            JSONObject object = new JSONObject();
            object.put("type", "HUD_HELLO");
            object.put("id", android.os.Build.MODEL);
            object.put("stream_port", 5062);
            object.put("protocol", 7);
            object.put("local_ip", localIp);

            byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(
                bytes,
                bytes.length,
                senderAddress,
                senderPort
            ));

            unicastHelloCount++;
        } catch (Exception error) {
            lastError = "unicast hello: " + safeMessage(error);
        } finally {
            if (socket != null) socket.close();
        }
    }

    private void receiveLoop() {
        try {
            receiveSocket = new DatagramSocket(null);
            receiveSocket.setReuseAddress(true);
            receiveSocket.bind(new InetSocketAddress("0.0.0.0", 5062));
            receiveSocket.setReceiveBufferSize(2 * 1024 * 1024);
            byte[] buf = new byte[65507];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                receiveSocket.receive(packet);
                receivedCount++;
                senderIp = packet.getAddress().getHostAddress();

                if (isImagePacket(packet)) {
                    handleImagePacket(packet);
                } else {
                    handleJsonPacket(packet);
                }

                cleanupAssemblies();
                if (receivedCount % 20 == 0) updateDiagnostics();
            }
        } catch (Exception error) {
            if (running) {
                lastError = "receive: " + safeMessage(error);
                updateDiagnostics();
            }
        }
    }

    private boolean isImagePacket(DatagramPacket packet) {
        if (packet.getLength() < 18) return false;
        byte[] data = packet.getData();
        int offset = packet.getOffset();
        for (int i = 0; i < IMAGE_MAGIC.length; i++) if (data[offset + i] != IMAGE_MAGIC[i]) return false;
        return true;
    }

    private void handleImagePacket(DatagramPacket packet) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength()).order(ByteOrder.BIG_ENDIAN);
            buffer.position(4);
            int frameId = buffer.getInt();
            int chunkIndex = buffer.getShort() & 0xffff;
            int totalChunks = buffer.getShort() & 0xffff;
            int width = buffer.getShort() & 0xffff;
            int height = buffer.getShort() & 0xffff;
            int length = buffer.getShort() & 0xffff;
            if (totalChunks <= 0 || totalChunks > 512 || chunkIndex >= totalChunks || length > buffer.remaining()) return;

            byte[] chunk = new byte[length];
            buffer.get(chunk);
            ImageAssembly assembly = assemblies.get(frameId);
            if (assembly == null) {
                assembly = new ImageAssembly(frameId, totalChunks, width, height);
                assemblies.put(frameId, assembly);
            }
            if (assembly.add(chunkIndex, chunk)) {
                byte[] jpeg = assembly.combine();
                assemblies.remove(frameId);
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bitmap != null) {
                    imageFrameCount++;
                    view.postHeaderBitmap(bitmap);
                    view.postConnected(senderIp);
                }
            }
            imageChunkCount++;
        } catch (Exception error) {
            lastError = "image: " + safeMessage(error);
        }
    }

    private void handleJsonPacket(DatagramPacket packet) {
        try {
            String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            JSONObject object = new JSONObject(text);
            String type = object.optString("type");
            if ("NAV_SOURCE_BEACON".equals(type)) {
                beaconCount++;
                int senderPort = object.optInt("sender_port", 5060);
                sendHelloToSender(packet.getAddress(), senderPort);
                view.postConnected(senderIp);
            } else if ("hud_semantic".equals(type) || "hud_header".equals(type) || "nav_frame".equals(type)) {
                frameCount++;
                view.postConnected(senderIp);
                view.postFrame(object);
            } else if ("NAV_SOURCE_ACK".equals(type)) {
                ackCount++;
                view.postConnected(senderIp);
            } else if ("NAV_SOURCE_STATUS".equals(type)) {
                statusCount++;
                senderStatus = object.optString("capture_status", "unknown");
                view.postConnected(senderIp);
            }
        } catch (Exception error) {
            lastError = "json: " + safeMessage(error);
        }
    }

    private void cleanupAssemblies() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, ImageAssembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().createdAt > 2500) iterator.remove();
        }
    }

    private String findLocalIpv4() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    String value = address.getHostAddress();
                    if (!address.isLoopbackAddress() && value != null && value.indexOf(':') < 0) return value;
                }
            }
        } catch (Exception ignored) { }
        return "unknown";
    }

    private String findBroadcastAddress() {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wifi == null ? null : wifi.getDhcpInfo();
            if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                byte[] quads = new byte[4];
                for (int i = 0; i < 4; i++) quads[i] = (byte) ((broadcast >> (i * 8)) & 0xff);
                return InetAddress.getByAddress(quads).getHostAddress();
            }
        } catch (Exception ignored) { }
        if (!"unknown".equals(localIp)) {
            int dot = localIp.lastIndexOf('.');
            if (dot > 0) return localIp.substring(0, dot + 1) + "255";
        }
        return "unknown";
    }

    private void updateDiagnostics() {
        view.postDiagnostics(
            "Local IP: " + localIp +
            "\nBroadcast: " + broadcastIp +
            "\nBroadcast HELLO sent: " + helloCount +
            "\nSender beacons received: " + beaconCount +
            "\nUnicast HELLO sent: " + unicastHelloCount +
            "\nSender: " + senderIp +
            "\nReceived: " + receivedCount +
            "\nACK: " + ackCount + "  Status: " + statusCount + "  Metadata: " + frameCount +
            "\nHeader images: " + imageFrameCount + "  Chunks: " + imageChunkCount +
            "\nSender capture: " + senderStatus +
            "\nLast error: " + lastError
        );
    }

    private String safeMessage(Exception error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value;
    }

    private static class ImageAssembly {
        final int frameId;
        final byte[][] chunks;
        final boolean[] received;
        final int width;
        final int height;
        final long createdAt = System.currentTimeMillis();
        int receivedCount;

        ImageAssembly(int id, int totalChunks, int imageWidth, int imageHeight) {
            frameId = id;
            chunks = new byte[totalChunks][];
            received = new boolean[totalChunks];
            width = imageWidth;
            height = imageHeight;
        }

        boolean add(int index, byte[] data) {
            if (!received[index]) {
                received[index] = true;
                chunks[index] = data;
                receivedCount++;
            }
            return receivedCount == chunks.length;
        }

        byte[] combine() throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] chunk : chunks) {
                if (chunk == null) throw new IllegalStateException("missing chunk");
                out.write(chunk);
            }
            return out.toByteArray();
        }
    }
}
