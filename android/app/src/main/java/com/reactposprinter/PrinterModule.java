package com.reactposprinter;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.gertec.flutterprinter.flutter_printer.BluetoothPrinterManager;
import com.gertec.flutterprinter.flutter_printer.GertecPrinterManager;
import com.gertec.flutterprinter.flutter_printer.NetworkPrinterManager;
import com.gertec.flutterprinter.flutter_printer.PaxPrinterManager;
import com.gertec.flutterprinter.flutter_printer.PositivoPrinterManager;
import com.gertec.flutterprinter.flutter_printer.SunmiPrinterManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** React Native bridge for Bluetooth, TCP/9100 and embedded POS printer APIs. */
public final class PrinterModule extends ReactContextBaseJavaModule {
    private final BluetoothPrinterManager bluetooth;
    private final NetworkPrinterManager network = new NetworkPrinterManager();
    private final SunmiPrinterManager sunmi;
    private final PaxPrinterManager pax;
    private final GertecPrinterManager gertec;
    private final PositivoPrinterManager positivo;

    PrinterModule(ReactApplicationContext context) {
        super(context);
        bluetooth = new BluetoothPrinterManager(context);
        sunmi = new SunmiPrinterManager(context);
        pax = new PaxPrinterManager(context);
        gertec = new GertecPrinterManager(context);
        positivo = new PositivoPrinterManager(context);
    }

    @NonNull @Override public String getName() { return "POSPrinter"; }

    private MethodChannel.MethodCallHandler manager(String method) {
        switch (method) {
            case "network": return network;
            case "sunmiInternal": return sunmi;
            case "paxInternal": return pax;
            case "gertecInternal": return gertec;
            case "positivoInternal": return positivo;
            case "auto":
            case "bluetooth":
            case "nativeJava":
            case "flutterPackage":
            default: return bluetooth;
        }
    }

    private String nativeMethod(String transport, String action) {
        if ("sunmiInternal".equals(transport)) return "sunmi" + capitalize(action);
        if ("paxInternal".equals(transport)) return "pax" + capitalize(action);
        if ("gertecInternal".equals(transport)) return "gertec" + capitalize(action);
        if ("positivoInternal".equals(transport)) return "positivo" + capitalize(action);
        return action;
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void invoke(String transport, String action, Map<String, Object> args, Promise promise) {
        manager(transport).onMethodCall(
                new MethodCall(nativeMethod(transport, action), args),
                new PromiseResult(promise));
    }

    @ReactMethod public void getDeviceInfo(Promise promise) {
        bluetooth.onMethodCall(new MethodCall("getDeviceInfo", null), new PromiseResult(promise));
    }

    @ReactMethod public void hasPermission(String transport, Promise promise) {
        invoke(transport, "hasPermission", null, promise);
    }

    @ReactMethod public void isBluetoothEnabled(String transport, Promise promise) {
        invoke(transport, "isBluetoothEnabled", null, promise);
    }

    @ReactMethod public void listDevices(String transport, Promise promise) {
        if (transport.endsWith("Internal")) {
            List<Map<String, String>> devices = new ArrayList<>();
            Map<String, String> device = new HashMap<>();
            device.put("name", "Impressora interna do terminal");
            device.put("address", "internal");
            devices.add(device);
            promise.resolve(toNative(devices));
            return;
        }
        invoke(transport, "listPairedDevices", null, promise);
    }

    @ReactMethod public void connect(String transport, String address, Promise promise) {
        Map<String, Object> args = new HashMap<>();
        args.put("address", address);
        invoke(transport, "connect", args, promise);
    }

    @ReactMethod public void disconnect(String transport, Promise promise) {
        invoke(transport, "disconnect", null, promise);
    }

    @ReactMethod public void isConnected(String transport, Promise promise) {
        invoke(transport, "isConnected", null, promise);
    }

    @ReactMethod public void printBytes(String transport, ReadableArray input, Promise promise) {
        List<Integer> bytes = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) bytes.add(input.getInt(i) & 0xff);
        Map<String, Object> args = new HashMap<>();
        args.put("bytes", bytes);
        invoke(transport, "printBytes", args, promise);
    }

    @Override public void invalidate() {
        bluetooth.disconnect();
        network.disconnect();
        super.invalidate();
    }

    private static Object toNative(Object value) {
        if (value == null || value instanceof Boolean || value instanceof String || value instanceof Number) {
            return value;
        }
        if (value instanceof Map) {
            WritableMap output = Arguments.createMap();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                put(output, String.valueOf(entry.getKey()), entry.getValue());
            }
            return output;
        }
        if (value instanceof List) {
            WritableArray output = Arguments.createArray();
            for (Object item : (List<?>) value) push(output, item);
            return output;
        }
        return String.valueOf(value);
    }

    private static void put(WritableMap map, String key, Object value) {
        Object nativeValue = toNative(value);
        if (nativeValue == null) map.putNull(key);
        else if (nativeValue instanceof Boolean) map.putBoolean(key, (Boolean) nativeValue);
        else if (nativeValue instanceof Number) map.putDouble(key, ((Number) nativeValue).doubleValue());
        else if (nativeValue instanceof WritableMap) map.putMap(key, (WritableMap) nativeValue);
        else if (nativeValue instanceof WritableArray) map.putArray(key, (WritableArray) nativeValue);
        else map.putString(key, String.valueOf(nativeValue));
    }

    private static void push(WritableArray array, Object value) {
        Object nativeValue = toNative(value);
        if (nativeValue == null) array.pushNull();
        else if (nativeValue instanceof Boolean) array.pushBoolean((Boolean) nativeValue);
        else if (nativeValue instanceof Number) array.pushDouble(((Number) nativeValue).doubleValue());
        else if (nativeValue instanceof WritableMap) array.pushMap((WritableMap) nativeValue);
        else if (nativeValue instanceof WritableArray) array.pushArray((WritableArray) nativeValue);
        else array.pushString(String.valueOf(nativeValue));
    }

    private static final class PromiseResult implements MethodChannel.Result {
        private final Promise promise;
        private boolean completed;
        PromiseResult(Promise promise) { this.promise = promise; }
        @Override public void success(Object result) {
            if (completed) return;
            completed = true;
            promise.resolve(toNative(result));
        }
        @Override public void error(String code, String message, Object details) {
            if (completed) return;
            completed = true;
            promise.reject(code, message == null ? code : message);
        }
        @Override public void notImplemented() {
            if (completed) return;
            completed = true;
            promise.reject("NOT_IMPLEMENTED", "Método de impressão não implementado.");
        }
    }
}
