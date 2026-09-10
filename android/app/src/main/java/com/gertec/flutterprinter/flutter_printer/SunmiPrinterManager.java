package com.gertec.flutterprinter.flutter_printer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.sunmi.peripheral.printer.InnerPrinterCallback;
import com.sunmi.peripheral.printer.InnerPrinterException;
import com.sunmi.peripheral.printer.InnerPrinterManager;
import com.sunmi.peripheral.printer.SunmiPrinterService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Ponte para a impressora interna de terminais SUNMI, via SDK oficial
 * (com.sunmi:printerlibrary — AIDL woyou.aidlservice.jiuiv5).
 *
 * Ao contrário do PAX, o SDK da SUNMI aceita bytes ESC/POS crus através de
 * sendRAWData — então os mesmos bytes gerados por EscPosFormat (Dart) são
 * enviados sem tradução nenhuma, exatamente como no Bluetooth/rede.
 */
public class SunmiPrinterManager implements MethodChannel.MethodCallHandler {

    private static final String TAG = "SunmiPrinterManager";
    private static final int BIND_TIMEOUT_MS = 3000;
    private static final int BIND_POLL_INTERVAL_MS = 50;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile SunmiPrinterService service;

    private final InnerPrinterCallback callback = new InnerPrinterCallback() {
        @Override
        protected void onConnected(SunmiPrinterService sunmiPrinterService) {
            service = sunmiPrinterService;
        }

        @Override
        protected void onDisconnected() {
            service = null;
        }
    };

    public SunmiPrinterManager(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "sunmiConnect":
                connect(result);
                break;
            case "sunmiDisconnect":
                disconnect(result);
                break;
            case "sunmiIsConnected":
                result.success(service != null);
                break;
            case "sunmiPrintBytes":
                List<Integer> bytesArg = call.argument("bytes");
                printBytes(bytesArg, result);
                break;
            default:
                result.notImplemented();
        }
    }

    private void connect(MethodChannel.Result result) {
        if (service != null) {
            result.success(true);
            return;
        }
        try {
            boolean bound = InnerPrinterManager.getInstance().bindService(context, callback);
            if (!bound) {
                result.success(false);
                return;
            }
            // bindService() é assíncrono: onConnected só chega depois. Espera
            // até BIND_TIMEOUT_MS em background antes de responder ao Dart.
            executor.execute(() -> {
                long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
                while (service == null && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(BIND_POLL_INTERVAL_MS);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                boolean connected = service != null;
                mainHandler.post(() -> result.success(connected));
            });
        } catch (InnerPrinterException e) {
            Log.e(TAG, "Falha ao vincular ao serviço de impressão SUNMI", e);
            result.error("NO_SUNMI_SERVICE", e.getMessage(), null);
        }
    }

    private void disconnect(MethodChannel.Result result) {
        try {
            InnerPrinterManager.getInstance().unBindService(context, callback);
        } catch (InnerPrinterException ignored) {
        } finally {
            service = null;
        }
        result.success(true);
    }

    private void printBytes(List<Integer> bytesArg, MethodChannel.Result result) {
        SunmiPrinterService currentService = service;
        if (currentService == null) {
            result.error("NOT_CONNECTED", "Impressora interna SUNMI não conectada.", null);
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
        try {
            currentService.sendRAWData(bytes, null);
            result.success(true);
        } catch (Exception e) {
            Log.e(TAG, "Falha ao imprimir na impressora interna SUNMI", e);
            result.error("WRITE_FAILED", e.getMessage(), null);
        }
    }
}
