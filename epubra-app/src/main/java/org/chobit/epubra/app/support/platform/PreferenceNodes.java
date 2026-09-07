package org.chobit.epubra.app.support.platform;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Preferences 节点入口。
 *
 * <p>Windows 的注册表后端在受限环境中可能抛 {@link SecurityException}。偏好设置不应
 * 阻断应用启动，因此这里统一探测系统后端；不可用时退回进程内存存储。
 */
public final class PreferenceNodes {

    private static volatile Preferences rootOverride;
    private static volatile Preferences memoryRoot;
    private static volatile boolean forceMemoryFallback;

    private PreferenceNodes() {
    }

    public static Preferences node(String absolutePath) {
        Preferences override = rootOverride;
        if (override != null) {
            return override.node(absolutePath);
        }
        if (forceMemoryFallback) {
            return memoryRoot().node(absolutePath);
        }
        try {
            Preferences node = Preferences.userRoot().node(absolutePath);
            probe(node);
            return node;
        } catch (BackingStoreException | RuntimeException e) {
            forceMemoryFallback = true;
            return memoryRoot().node(absolutePath);
        }
    }

    public static void useInMemoryForTesting() {
        rootOverride = new MemoryPreferences(null, "");
        forceMemoryFallback = false;
    }

    public static void resetForTesting() {
        rootOverride = null;
        memoryRoot = null;
        forceMemoryFallback = false;
    }

    private static void probe(Preferences node) throws BackingStoreException {
        String key = "__epubra_probe__";
        node.put(key, "1");
        node.remove(key);
        node.flush();
    }

    private static Preferences memoryRoot() {
        Preferences root = memoryRoot;
        if (root == null) {
            synchronized (PreferenceNodes.class) {
                root = memoryRoot;
                if (root == null) {
                    root = new MemoryPreferences(null, "");
                    memoryRoot = root;
                }
            }
        }
        return root;
    }

    private static final class MemoryPreferences extends AbstractPreferences {

        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryPreferences> children = new HashMap<>();

        private MemoryPreferences(AbstractPreferences parent, String name) {
            super(parent, name);
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
            children.clear();
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(String[]::new);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, n -> new MemoryPreferences(this, n));
        }

        @Override
        protected void syncSpi() {
        }

        @Override
        protected void flushSpi() {
        }
    }
}
