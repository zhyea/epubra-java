package org.chobit.epubra.app.controller;

import org.chobit.epubra.app.ui.ToolbarIcons;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 两个「插入图片」入口的分工契约。
 *
 * <p>编辑栏「图片」按钮 = 从本机选图（{@code onInsertImageFromDisk}）；
 * 菜单栏「插入图片」与资源面板按钮 = 从资源列表插入（{@code onInsertImage}）。
 * 两个入口名字接近，**改错不会报错**，只会静默走错路径，所以把绑定钉死。
 *
 * <p>这里只读 FXML 结构、不启动 JavaFX。handler 方法是否真实存在由
 * {@link VisualEditorTabUiTest} / {@link UndoRefreshUiTest} 的加载动作覆盖——
 * FXMLLoader 解析 {@code #method} 失败会直接抛 LoadException。
 */
class InsertImageEntryWiringTest {

    @Test
    @DisplayName("编辑栏图片按钮指向「从本机选图」入口，菜单栏仍是「从资源列表插入」入口")
    void imageEntryPointsToExpectedHandlers() throws Exception {
        Document fxml = loadMainWindowFxml();

        assertEquals("#onInsertImageFromDisk",
                attributeOf(fxml, "//Button[@id='image']", "onAction"),
                "编辑栏「图片」应弹文件选择器，从本机选图");
        assertEquals("#onInsertImage",
                attributeOf(fxml, "//MenuItem[@text='插入图片']", "onAction"),
                "菜单栏「插入图片」按约定保持从资源列表插入");
    }

    @Test
    @DisplayName("编辑栏工具条的每个按钮都配好了图标与悬停提示")
    void toolbarButtonsAllHaveIconsAndTooltips() throws Exception {
        Document fxml = loadMainWindowFxml();
        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList buttons = (NodeList) xpath.evaluate(
                "//FlowPane[@styleClass='editor-toolbar']//Button", fxml, XPathConstants.NODESET);
        // 13 个格式按钮（含「清除格式」）+ 图片 / 撤销 / 重做三个动作按钮 + 放大字号 /
        // 缩小字号两个档位按钮。
        // 字体（ComboBox）与颜色（ColorPicker）、对齐（MenuButton）不是 Button，不在这个 XPath 里——
        // 它们的契约由 VisualEditorTabUiTest#styleControlsCarryOptionsAndCommands 守。
        assertEquals(18, buttons.getLength(), "工具条应有 18 个按钮");
        for (int i = 0; i < buttons.getLength(); i++) {
            String id = ((Element) buttons.item(i)).getAttribute("id");
            assertTrue(ToolbarIcons.covers(id),
                    "按钮 " + id + " 在 ToolbarIcons 里缺少图形路径或悬停提示文案");
        }
    }

    private static Document loadMainWindowFxml() throws Exception {
        try (InputStream in = InsertImageEntryWiringTest.class.getResourceAsStream(
                "/org/chobit/epubra/app/view/main-window.fxml")) {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
    }

    private static String attributeOf(Document doc, String expression, String attribute)
            throws Exception {
        XPath xpath = XPathFactory.newInstance().newXPath();
        Element element = (Element) xpath.evaluate(expression, doc, XPathConstants.NODE);
        if (element == null) {
            fail("FXML 里找不到元素：" + expression);
        }
        return element.getAttribute(attribute);
    }
}
