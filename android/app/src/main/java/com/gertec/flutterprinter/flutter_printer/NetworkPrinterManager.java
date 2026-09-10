package com.gertec.flutterprinter.flutter_printer;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Transporte ESC/POS RAW via TCP, incluindo descoberta da sub-rede na porta 9100. */
public class NetworkPrinterManager implements MethodChannel.MethodCallHandler {
    private static final int DEFAULT_PORT = 9100;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private volatile Socket socket;
    private volatile OutputStream outputStream;

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "hasPermission":
            case "requestPermission":
            case "isBluetoothEnabled": result.success(true); break;
            case "listPairedDevices": scan(result); break;
            case "connect": connect(call.argument("address"), result); break;
            case "disconnect": disconnect(result); break;
            case "isConnected": result.success(socket != null && socket.isConnected() && !socket.isClosed()); break;
            case "printBytes": printBytes(call.argument("bytes"), result); break;
            default: result.notImplemented();
        }
    }

    private void scan(MethodChannel.Result result) {
        ioExecutor.execute(() -> {
            String localIp = localIPv4();
            if (localIp == null || localIp.lastIndexOf('.') < 0) {
                mainHandler.post(() -> result.success(Collections.emptyList()));
                return;
            }
            String prefix = localIp.substring(0, localIp.lastIndexOf('.') + 1);
            ExecutorService scanners = Executors.newFixedThreadPool(32);
            List<Map<String, String>> found = Collections.synchronizedList(new ArrayList<>());
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + i;
                if (ip.equals(localIp)) continue;
                scanners.execute(() -> {
                    try (Socket probe = new Socket()) {
                        probe.connect(new InetSocketAddress(ip, DEFAULT_PORT), 350);
                        Map<String, String> device = new HashMap<>();
                        device.put("name", "Impressora de rede");
                        device.put("address", ip + ":" + DEFAULT_PORT);
                        found.add(device);
                    } catch (IOException ignored) {}
                });
            }
            scanners.shutdown();
            try { scanners.awaitTermination(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mainHandler.post(() -> result.success(found));
        });
    }

    private static String localIPv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void connect(String address, MethodChannel.Result result) {
        ioExecutor.execute(() -> {
            closeQuietly();
            try {
                String value = address == null ? "" : address.trim();
                int separator = value.lastIndexOf(':');
                String host = separator > 0 ? value.substring(0, separator) : value;
                int port = separator > 0 ? Integer.parseInt(value.substring(separator + 1)) : DEFAULT_PORT;
                if (host.isEmpty()) throw new IOException("IP da impressora não informado.");
                Socket newSocket = new Socket();
                newSocket.connect(new InetSocketAddress(host, port), 5000);
                socket = newSocket;
                outputStream = newSocket.getOutputStream();
                mainHandler.post(() -> result.success(true));
            } catch (Exception error) {
                closeQuietly();
                mainHandler.post(() -> result.error("CONNECT_FAILED", error.getMessage(), null));
            }
        });
    }

    private void printBytes(List<Integer> values, MethodChannel.Result result) {
        OutputStream stream = outputStream;
        if (stream == null) { result.error("NOT_CONNECTED", "Nenhuma impressora de rede conectada.", null); return; }
        if (values == null) { result.error("INVALID_ARGUMENT", "Nenhum dado informado.", null); return; }
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) bytes[i] = (byte) (int) values.get(i);
        ioExecutor.execute(() -> {
            try {
                stream.write(bytes);
                stream.flush();
                mainHandler.post(() -> result.success(true));
            } catch (IOException error) {
                closeQuietly();
                mainHandler.post(() -> result.error("WRITE_FAILED", error.getMessage(), null));
            }
        });
    }

    private void disconnect(MethodChannel.Result result) {
        ioExecutor.execute(() -> { closeQuietly(); mainHandler.post(() -> result.success(true)); });
    }

    public void disconnect() { ioExecutor.execute(this::closeQuietly); }

    private void closeQuietly() {
        try { if (outputStream != null) outputStream.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        outputStream = null;
        socket = null;
    }
}
