package io.flutter.plugin.common;

/** Minimal callback contract that lets the proven Flutter Java drivers run in React Native. */
public final class MethodChannel {
    private MethodChannel() {}

    public interface MethodCallHandler {
        void onMethodCall(MethodCall call, Result result);
    }

    public interface Result {
        void success(Object result);
        void error(String code, String message, Object details);
        void notImplemented();
    }
}
