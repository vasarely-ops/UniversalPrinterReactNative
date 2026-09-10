package com.gertec.flutterprinter.flutter_printer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Implementação nativa em Java da conexão com impressoras térmicas Bluetooth
 * (perfil clássico SPP), usada como alternativa ao pacote Flutter
 * print_bluetooth_thermal. Todo o trabalho de socket roda fora da main
 * thread; os resultados voltam ao Dart pela main thread, como o
 * MethodChannel exige.
 */
public class BluetoothPrinterManager implements MethodChannel.MethodCallHandler {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int PERMISSION_REQUEST_CODE = 4321;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile BluetoothSocket socket;
    private volatile OutputStream outputStream;
    private MethodChannel.Result pendingPermissionResult;

    public BluetoothPrinterManager(Context context) {
        this.context = context;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "hasPermission":
                result.success(hasBluetoothConnectPermission());
                break;
            case "requestPermission":
                requestPermission(result);
                break;
            case "isBluetoothEnabled":
                BluetoothAdapter adapter = getAdapter();
                result.success(adapter != null && adapter.isEnabled());
                break;
            case "listPairedDevices":
                listPairedDevices(result);
                break;
            case "connect":
                String address = call.argument("address");
                connect(address, result);
                break;
            case "disconnect":
                disconnect(result);
                break;
            case "isConnected":
                result.success(socket != null && socket.isConnected());
                break;
            case "printBytes":
                List<Integer> bytesArg = call.argument("bytes");
                printBytes(bytesArg, result);
                break;
            case "getDeviceInfo":
                getDeviceInfo(result);
                break;
            default:
                result.notImplemented();
        }
    }

    /**
     * Identificação do fabricante/modelo do terminal, usada no lado Dart
     * para escolher o perfil de impressão (limite de escala de fonte,
     * altura das tiras de imagem) por marca — POS de fabricantes
     * diferentes divergem no que suportam de ESC/POS.
     */
    private void getDeviceInfo(MethodChannel.Result result) {
        Map<String, String> info = new HashMap<>();
        info.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
        info.put("brand", Build.BRAND == null ? "" : Build.BRAND);
        info.put("model", Build.MODEL == null ? "" : Build.MODEL);
        result.success(info);
    }

    private BluetoothAdapter getAdapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // Em versões anteriores ao Android 12, BLUETOOTH/BLUETOOTH_ADMIN são
        // permissões normais, concedidas automaticamente na instalação.
        return true;
    }

    private void requestPermission(MethodChannel.Result result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasBluetoothConnectPermission()) {
            result.success(true);
            return;
        }
        pendingPermissionResult = result;
        if (!(context instanceof Activity)) {
            result.error("NO_ACTIVITY", "Solicite a permissão Bluetooth pela interface React Native.", null);
            return;
        }
        ActivityCompat.requestPermissions(
                (Activity) context,
                new String[] {android.Manifest.permission.BLUETOOTH_CONNECT},
                PERMISSION_REQUEST_CODE);
    }

    /** Chamado por MainActivity#onRequestPermissionsResult. */
    public void onRequestPermissionsResult(int requestCode, @NonNull int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST_CODE || pendingPermissionResult == null) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        pendingPermissionResult.success(granted);
        pendingPermissionResult = null;
    }

    private void listPairedDevices(MethodChannel.Result result) {
        BluetoothAdapter adapter = getAdapter();
        if (adapter == null) {
            result.error("NO_ADAPTER", "Este dispositivo não possui suporte a Bluetooth.", null);
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            result.error("NO_PERMISSION", "Permissão BLUETOOTH_CONNECT não concedida.", null);
            return;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            List<Map<String, String>> devices = new ArrayList<>();
            for (BluetoothDevice device : bonded) {
                Map<String, String> map = new HashMap<>();
                map.put("name", device.getName() != null ? device.getName() : device.getAddress());
                map.put("address", device.getAddress());
                devices.add(map);
            }
            result.success(devices);
        } catch (SecurityException e) {
            result.error("NO_PERMISSION", e.getMessage(), null);
        }
    }

    private void connect(String address, MethodChannel.Result result) {
        if (address == null || address.isEmpty()) {
            result.error("INVALID_ARGUMENT", "Endereço MAC não informado.", null);
            return;
        }
        BluetoothAdapter adapter = getAdapter();
        if (adapter == null) {
            result.error("NO_ADAPTER", "Este dispositivo não possui suporte a Bluetooth.", null);
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            result.error("NO_PERMISSION", "Permissão BLUETOOTH_CONNECT não concedida.", null);
            return;
        }

        executor.execute(() -> {
            try {
                closeQuietly();
                BluetoothDevice device = adapter.getRemoteDevice(address);
                adapter.cancelDiscovery();
                BluetoothSocket newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                newSocket.connect();
                socket = newSocket;
                outputStream = newSocket.getOutputStream();
                mainHandler.post(() -> result.success(true));
            } catch (IOException | SecurityException e) {
                closeQuietly();
                mainHandler.post(() -> result.error("CONNECT_FAILED", e.getMessage(), null));
            }
        });
    }

    private void printBytes(List<Integer> bytesArg, MethodChannel.Result result) {
        OutputStream stream = outputStream;
        if (stream == null) {
            result.error("NOT_CONNECTED", "Nenhuma impressora conectada.", null);
            return;
        }
        if (bytesArg == null) {
            result.error("INVALID_ARGUMENT", "Nenhum dado informado para impressão.", null);
            return;
        }
        byte[] bytes = new byte[bytesArg.size()];
        for (int i = 0; i < bytesArg.size(); i++) {
            bytes[i] = (byte) (int) bytesArg.get(i);
        }
        executor.execute(() -> {
            try {
                stream.write(bytes);
                stream.flush();
                mainHandler.post(() -> result.success(true));
            } catch (IOException e) {
                mainHandler.post(() -> result.error("WRITE_FAILED", e.getMessage(), null));
            }
        });
    }

    private void disconnect(MethodChannel.Result result) {
        executor.execute(() -> {
            closeQuietly();
            mainHandler.post(() -> result.success(true));
        });
    }

    /** Chamado a partir de MainActivity#onDestroy, sem callback ao Dart. */
    public void disconnect() {
        executor.execute(this::closeQuietly);
    }

    private void closeQuietly() {
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        outputStream = null;
        socket = null;
    }
}
