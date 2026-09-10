package io.flutter.plugin.common;

import java.util.Map;

/** Minimal compatibility type used by the printer drivers migrated from Flutter. */
public final class MethodCall {
    public final String method;
    private final Object arguments;

    public MethodCall(String method, Object arguments) {
        this.method = method;
        this.arguments = arguments;
    }

    @SuppressWarnings("unchecked")
    public <T> T argument(String key) {
        if (!(arguments instanceof Map)) return null;
        return (T) ((Map<?, ?>) arguments).get(key);
    }
}
