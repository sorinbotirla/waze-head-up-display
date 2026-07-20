package com.interfaz.wazespeedoverlay;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.DhcpInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

public class FlirCamUdpTransport {
    private static FlirCamUdpTransport instance;

    private Context context;
    private Handler handler = new Handler(Looper.getMainLooper());
    private DatagramSocket sendSocket;
    private DatagramSocket discoverySocket;
    private InetAddress flirAddress;
    private int flirSpeedPort = 5055;
    private String lastStatus = "UDP waiting for FLIRCAM_HELLO";
    private boolean running = false;

    public static FlirCamUdpTransport get(Context ctx) {
        if (instance == null) {
            instance = new FlirCamUdpTransport(ctx.getApplicationContext());
        }
        return instance;
    }

    private FlirCamUdpTransport(Context ctx) {
        context = ctx;
        start();
    }

    public String getStatus() {
        return lastStatus;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;

        new Thread(new Runnable() {
            public void run() {
                listenForDiscovery();
            }
        }, "FlirCamDiscovery").start();

        handler.postDelayed(new Runnable() {
            public void run() {
                tryResolveHostname();
                if (running) {
                    handler.postDelayed(this, 5000);
                }
            }
        }, 1000);
    }

    private void listenForDiscovery() {
        try {
            discoverySocket = new DatagramSocket(5056);
            discoverySocket.setBroadcast(true);

            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                discoverySocket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8").trim();
                handleDiscovery(msg, packet.getAddress());
            }
        } catch (Exception e) {
            lastStatus = "Discovery error: " + e.getMessage();
        }
    }

    private void handleDiscovery(String msg, InetAddress addr) {
        if (msg == null || msg.length() == 0) {
            return;
        }

        boolean ok = false;
        int port = 5055;

        if (msg.indexOf("FLIRCAM_HELLO") >= 0 || msg.indexOf("flircam") >= 0) {
            ok = true;
            try {
                JSONObject o = new JSONObject(msg);
                port = o.optInt("speed_port", 5055);
            } catch (Exception ignored) {}
        }

        if (!ok) {
            return;
        }

        flirAddress = addr;
        flirSpeedPort = port;
        lastStatus = "FLIR found: " + flirAddress.getHostAddress() + ":" + flirSpeedPort;
    }

    private void tryResolveHostname() {
        if (flirAddress != null) {
            return;
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    InetAddress a = InetAddress.getByName("flircam.local");
                    if (a != null) {
                        flirAddress = a;
                        flirSpeedPort = 5055;
                        lastStatus = "FLIR resolved: " + a.getHostAddress() + ":5055";
                    }
                } catch (UnknownHostException ignored) {}
            }
        }, "FlirCamResolve").start();
    }

    public void sendSpeed(final String speed, final int confidence, final String source) {
        if (speed == null || speed.equals("--")) {
            sendSpeedValue(0, 0, source);
            return;
        }

        try {
            int v = Integer.parseInt(speed);
            sendSpeedValue(v, confidence, source);
        } catch (Exception ignored) {}
    }

    private void sendSpeedValue(final int limit, final int confidence, final String source) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (sendSocket == null) {
                        sendSocket = new DatagramSocket();
                        sendSocket.setBroadcast(true);
                    }

                    JSONObject o = new JSONObject();
                    o.put("type", "SPEED_LIMIT");
                    o.put("limit", limit);
                    o.put("confidence", confidence);
                    o.put("source", source == null ? "waze_accessibility" : source);
                    o.put("ts", System.currentTimeMillis());

                    byte[] data = o.toString().getBytes("UTF-8");

                    if (flirAddress != null) {
                        DatagramPacket p = new DatagramPacket(data, data.length, flirAddress, flirSpeedPort);
                        sendSocket.send(p);
                        lastStatus = "UDP sent " + limit + " to " + flirAddress.getHostAddress();
                    } else {
                        InetAddress bcast = getBroadcastAddress();
                        DatagramPacket p = new DatagramPacket(data, data.length, bcast, 5055);
                        sendSocket.send(p);
                        lastStatus = "UDP broadcast " + limit;
                    }
                } catch (Exception e) {
                    lastStatus = "UDP send error: " + e.getMessage();
                }
            }
        }, "FlirCamSend").start();
    }

    private InetAddress getBroadcastAddress() throws Exception {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp != null) {
                    int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                    byte[] quads = new byte[4];
                    for (int k = 0; k < 4; k++) {
                        quads[k] = (byte) ((broadcast >> (k * 8)) & 0xFF);
                    }
                    return InetAddress.getByAddress(quads);
                }
            }
        } catch (Exception ignored) {}
        return InetAddress.getByName("255.255.255.255");
    }
}
