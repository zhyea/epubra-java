package org.chobit.epubra.app.editor;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 样式值的纯换算契约：显示名 ↔ CSS 值、{@link Color} ↔ CSS 颜色串。
 *
 * <p>不建窗口、不启 WebView——这几处换算错了不会报错，只会把错误的值静默写进正文：
 * <ul>
 *   <li>字体族名不加引号 → CSS 把它当字体栈拆开（{@code Font.getFamilies()} 返回的名字
 *       过半数带空格，实测 167/258）；</li>
 *   <li>不透明度 0 写成 {@code rgba(…,0)} → 正文里多一条肉眼无差别的死声明；</li>
 *   <li>回显时没剥引号 → 下拉框里显示 {@code "SimSun"} 这种带引号的名字。</li>
 * </ul>
 *
 * <p>字体下拉「列全本机字体族」的契约由 {@code VisualEditorTabUiTest} 守——那条要真控件 + 活工具包。
 */
class EditorStyleValueTest {

    @Test
    @DisplayName("字体显示名 → CSS 值：一律加双引号；「默认」档是空串（= 删掉声明）")
    void cssFontValueQuotesFamilyNames() {
        assertEquals("", EditorStyleControls.cssFontValue(null), "null 应回默认档");
        assertEquals("", EditorStyleControls.cssFontValue(""), "空串应回默认档");
        assertEquals("", EditorStyleControls.cssFontValue("   "), "全空白应回默认档");
        assertEquals("", EditorStyleControls.cssFontValue(EditorStyleControls.FONT_DEFAULT),
                "「默认」档对应的 CSS 值是空串，不是某个字体名");

        assertEquals("\"SimSun\"", EditorStyleControls.cssFontValue("SimSun"),
                "单名也要加引号——统一一种形态，回显时不用分支判断");
        assertEquals("\"Microsoft YaHei\"", EditorStyleControls.cssFontValue("Microsoft YaHei"),
                "带空格的族名不加引号会被 CSS 当成两个字体");
        assertEquals("\"楷体\"", EditorStyleControls.cssFontValue("楷体"), "中文族名同样加引号");
        assertEquals("\"SimSun\"", EditorStyleControls.cssFontValue("  SimSun  "),
                "两端空白要修掉，否则引号里的名字对不上系统字体");
    }

    @Test
    @DisplayName("字体栈 → 显示名：取首段并剥掉引号（回显通道）")
    void firstFamilyStripsQuotes() {
        assertNull(EditorStyleControls.firstFamily(null));
        assertNull(EditorStyleControls.firstFamily("   "));
        assertEquals("SimSun", EditorStyleControls.firstFamily("SimSun"), "没有引号就原样用");
        assertEquals("SimSun", EditorStyleControls.firstFamily("\"SimSun\""));
        assertEquals("SimSun", EditorStyleControls.firstFamily("\"SimSun\", \"宋体\", serif"),
                "老正文里的字体栈只取首段");
        assertEquals("KaiTi", EditorStyleControls.firstFamily("'KaiTi', serif"),
                "单引号也要剥");
        assertEquals("Microsoft YaHei", EditorStyleControls.firstFamily("\"Microsoft YaHei\""));
    }

    @Test
    @DisplayName("Color → CSS 颜色：null / 全透明 = 清除（空串），其余给 #rrggbb")
    void toCssColorClearsOnNullAndZeroOpacity() {
        assertEquals("", EditorStyleControls.toCssColor(null),
                "没有颜色 = 空串（删掉 color 声明），不是白色");
        assertEquals("", EditorStyleControls.toCssColor(Color.rgb(0, 0, 0, 0.0)),
                "不透明度滑到 0 视为清除——rgba(…,0) 与「没设颜色」视觉上无异");
        assertEquals("#ff0000", EditorStyleControls.toCssColor(Color.RED));
        assertEquals("#ffffff", EditorStyleControls.toCssColor(Color.WHITE));
        assertEquals("#c00000", EditorStyleControls.toCssColor(Color.web("#c00000")));
    }

    @Test
    @DisplayName("CSS 颜色 → Color：空串 / 认不出的写法都给 null（取色器留空，不动正文原值）")
    void toFxColorIsLenient() {
        assertNull(EditorStyleControls.toFxColor(null));
        assertNull(EditorStyleControls.toFxColor("   "));
        assertNull(EditorStyleControls.toFxColor("not-a-color"),
                "源码区手写的怪值不该抛异常把回显打断");
        assertEquals(Color.web("#c00000"), EditorStyleControls.toFxColor("#c00000"));
        assertEquals(Color.web("rgb(192, 0, 0)"), EditorStyleControls.toFxColor("rgb(192, 0, 0)"),
                "浏览器认的写法取色器也要能认");
    }
}
