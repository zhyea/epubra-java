package org.chobit.epubra.app.ui;

import javafx.scene.control.Menu;
import javafx.scene.layout.Region;

/**
 * JavaFX 节点操作的小工具。
 *
 * <p>{@code visible} 与 {@code managed} 必须同步设置：只设 visible=false 的节点仍参与
 * 布局计算，会留下一段空白间距。项目里多处（状态栏分区、活动栏、预览区切换）都要这个
 * 组合，集中一处避免各控制器各写一份。
 */
public final class FxNodes {

    private FxNodes() {
    }

    /** 同步设置 visible 与 managed；node 为 null 时静默返回。 */
    public static void setVisibleManaged(Region node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * 只切 {@code visible}，用于 {@link Menu}。
     *
     * <p>{@code Menu} 不是 {@link Region}、也没有 {@code managed} 概念——{@code MenuBar} 自身
     * 就会把不可见的菜单从布局中过滤掉，不存在「隐藏但占位」的间距问题，所以不能套用
     * 上面的 visible+managed 组合。node 为 null 时静默返回。
     */
    public static void setVisible(Menu node, boolean visible) {
        if (node != null) {
            node.setVisible(visible);
        }
    }
}
