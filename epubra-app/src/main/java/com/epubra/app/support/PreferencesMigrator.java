package com.epubra.app.support;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Preferences 节点的一次性迁移工具。
 *
 * <p>从 {@code /com/epubra/app/support/Xxx} 路径迁移到 {@code /Epubra/Xxx}——后者是
 * Sprint 10 重命名后的新节点。{@link #migrate(Preferences, Preferences)} 是幂等的：
 * 只有当新节点为空、且旧节点非空时才执行；迁移完成后清空旧节点防止回退。
 *
 * <p>迁移由各 {@code preferences()} 方法在首次被调用时隐式触发，不需要单独调用入口。
 * Windows 注册表键从 {@code com\epubra\app\support\Xxx} → {@code Epubra\Xxx}，
 * macOS plist 同理迁移；Linux 文件 back-end 路径由 JDK 自动处理。
 *
 * <h2>设计决策</h2>
 * <ul>
 *   <li><b>不是 copy 而是 move</b>——旧节点用完即清，避免下次启动再触发迁移</li>
 *   <li><b>只搬运现有 key</b>——不搬 deleted/不存在 key；不创建旧节点的子节点（深递归不递归迁移子树）</li>
 *   <li><b>新节点已存在则跳过</b>——保证升级后用户改了新节点的值不会被旧值覆盖</li>
 *   <li><b>失败仅记录</b>——IO 失败不能阻塞主流程；偏好是"尽力而为"</li>
 * </ul>
 */
public final class PreferencesMigrator {

    private static final System.Logger LOG = System.getLogger(PreferencesMigrator.class.getName());

    private PreferencesMigrator() {
    }

    /**
     * 把 {@code legacy} 节点的所有 key 搬到 {@code target} 节点，然后清空 {@code legacy}。
     *
     * <p>幂等：只有当 {@code target} 完全为空、且 {@code legacy} 有内容时才执行。
     *
     * @param legacy 旧节点（{@code /com/epubra/app/support/Xxx}）
     * @param target 新节点（{@code /Epubra/Xxx}）
     */
    public static void migrate(Preferences legacy, Preferences target) {
        if (legacy == null || target == null || legacy == target) {
            return;
        }
        try {
            // 1. 如果新节点已经有数据,跳过(不要覆盖用户改动)
            if (target.keys().length > 0) {
                // 仍然清空旧节点:首次升级后用户用旧节点的可能性为零
                safeClear(legacy);
                return;
            }
            // 2. 旧节点为空,什么都不做
            String[] legacyKeys = legacy.keys();
            if (legacyKeys.length == 0) {
                return;
            }
            // 3. 搬运每一个 key
            for (String key : legacyKeys) {
                String value = legacy.get(key, null);
                if (value != null) {
                    target.put(key, value);
                }
            }
            // 4. 清空旧节点,防止下次启动再走迁移路径
            safeClear(legacy);
            try {
                target.flush();
            } catch (BackingStoreException flushEx) {
                // flush 失败仅记录;不影响下次启动的迁移(下次 target 已有数据会跳过)
                LOG.log(System.Logger.Level.WARNING,
                        "Preferences flush failed after migration: " + flushEx.getMessage(), flushEx);
            }
            LOG.log(System.Logger.Level.INFO,
                    "Preferences migrated: " + legacy.absolutePath() + " -> " + target.absolutePath()
                            + " (" + legacyKeys.length + " keys)");
        } catch (BackingStoreException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Preferences migration skipped: " + legacy.absolutePath()
                            + " -> " + target.absolutePath() + " (" + e.getMessage() + ")", e);
        }
    }

    private static void safeClear(Preferences node) {
        try {
            node.clear();
            node.flush();
        } catch (BackingStoreException ignored) {
            // 清理失败不致命——旧值仍存在但下次迁移检测到 target 非空会跳过
        }
    }
}