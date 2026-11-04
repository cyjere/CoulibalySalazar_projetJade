package bdi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BeliefBase {
    private final Map<String, Object> beliefs = new ConcurrentHashMap<>();

    public void set(String key, Object value) {
        if (key == null) return;           // ignore les clés null
        if (value == null) {
            beliefs.remove(key);           // null = suppression
        } else {
            beliefs.put(key, value);
        }
    }

    public Object get(String key) {
        if (key == null) return null;
        return beliefs.get(key);
    }

    public boolean has(String key) {
        return key != null && beliefs.containsKey(key);
    }

    public void remove(String key) {
        if (key != null) beliefs.remove(key);
    }

    public Map<String, Object> all() { return beliefs; }
}