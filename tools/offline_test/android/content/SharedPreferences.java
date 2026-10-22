package android.content;

import java.util.HashMap;
import java.util.Map;

public class SharedPreferences {
    private final Map<String, Object> m = new HashMap<String, Object>();
    public boolean getBoolean(String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }
    public String getString(String k, String def) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : def;
    }
    public int getInt(String k, int def) {
        Object v = m.get(k);
        return v instanceof Integer ? (Integer) v : def;
    }
    public Map<String, ?> getAll() { return m; }
    public Editor edit() { return new Editor(); }
    public class Editor {
        private final Map<String, Object> buf = new HashMap<String, Object>();
        public Editor putBoolean(String k, boolean v) { buf.put(k, v); return this; }
        public Editor putString(String k, String v) { buf.put(k, v); return this; }
        public Editor putInt(String k, int v) { buf.put(k, v); return this; }
        public Editor remove(String k) { buf.put(k, null); return this; }
        public boolean commit() {
            for (Map.Entry<String, Object> e : buf.entrySet()) {
                if (e.getValue() == null) m.remove(e.getKey());
                else m.put(e.getKey(), e.getValue());
            }
            buf.clear();
            return true;
        }
        public void apply() { commit(); }
    }
}
