package org.chobit.epubra.app.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            throw new AssertionError("FXML 里找不到元素：" + expression);
        }
        return element.getAttribute(attribute);
    }
}
