package com.gertec.flutterprinter.flutter_printer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import com.xcheng.printerservice.IPrinterCallback;
import com.xcheng.printerservice.IPrinterService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Ponte para o serviço AIDL interno de impressão de terminais POSITIVO
 * L300/L400/L500 (com.xcheng.printerservice), vendorizado a partir de
 * https://github.com/vasarely-ops/BluetoothUniversalPrinter/ (só a
 * interface .aidl — sem binário proprietário, o stub é gerado no build).
 *
 * Igual à SUNMI, esse serviço aceita bytes ESC/POS crus via sendRAWData,
 * então os mesmos bytes de EscPosFormat (Dart) valem aqui sem tradução.
 */
public class PositivoPrinterManager implements MethodChannel.MethodCallHandler {

    private static final String TAG = "PositivoPrinterManager";
    private static final String SERVICE_PACKAGE = "com.xcheng.printerservice";
    private static final String SERVICE_ACTION = "com.xcheng.printerservice.IPrinterService";
    private static final int BIND_TIMEOUT_MS = 3000;
    private static final int BIND_POLL_INTERVAL_MS = 50;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile IPrinterService service;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IPrinterService.Stub.asInterface(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    public PositivoPrinterManager(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "positivoConnect":
                connect(result);
                break;
            case "positivoDisconnect":
                disconnect(result);
                break;
            case "positivoIsConnected":
                result.success(service != null);
                break;
            case "positivoPrintBytes":
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
            Intent intent = new Intent();
            intent.setPackage(SERVICE_PACKAGE);
            intent.setAction(SERVICE_ACTION);
            boolean bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                result.success(false);
                return;
            }
            executor.execute(() -> {
                long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
                while (service == null && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(BIND_POLL_INTERVAL_MS);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                IPrinterService connected = service;
                if (connected != null) {
                    try {
                        connected.printerInit(null);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Falha ao inicializar impressora POSITIVO", e);
                    }
                }
                mainHandler.post(() -> result.success(connected != null));
            });
        } catch (Throwable e) {
            // Throwable de propósito: em aparelhos sem esse serviço instalado
            // (não POSITIVO), o bind falha de formas variadas.
            Log.e(TAG, "Falha ao conectar na impressora interna POSITIVO", e);
            result.error("NO_POSITIVO_PRINTER",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private void disconnect(MethodChannel.Result result) {
        try {
            if (service != null) {
                context.unbindService(serviceConnection);
            }
        } catch (IllegalArgumentException ignored) {
            // já estava desvinculado
        } finally {
            service = null;
        }
        result.success(true);
    }

    private void printBytes(List<Integer> bytesArg, MethodChannel.Result result) {
        IPrinterService currentService = service;
        if (currentService == null) {
            result.error("NOT_CONNECTED", "Impressora interna POSITIVO não conectada.", null);
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
                currentService.sendRAWData(bytes, new IPrinterCallback.Stub() {
                    private boolean resultSent = false;

                    @Override
                    public void onException(int code, String msg) {
                        finish(false, "Erro " + code + ": " + msg);
                    }

                    @Override
                    public void onLength(long current, long total) {
                    }

                    @Override
                    public void onRealLength(double realCurrent, double realTotal) {
                    }

                    @Override
                    public void onComplete() {
                        finish(true, null);
                    }

                    private void finish(boolean success, String error) {
                        if (resultSent) return;
                        resultSent = true;
                        mainHandler.post(() -> {
                            if (success) {
                                result.success(true);
                            } else {
                                result.error("WRITE_FAILED", error, null);
                            }
                        });
                    }
                });
            } catch (Throwable e) {
                Log.e(TAG, "Falha ao imprimir na impressora interna POSITIVO", e);
                mainHandler.post(() -> result.error("WRITE_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage(), null));
            }
        });
    }
}
