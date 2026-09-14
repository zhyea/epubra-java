package org.chobit.epubra.app;

import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用图标的契约：尺寸齐全、资源可加载、图形内容正确。
 *
 * <p><b>为什么值得测</b>：图标是「改了看不见、漏了也不报错」的东西——资源没打进 jar、
 * 或者 {@code tools/make-icon.py} 改了设计却忘了重新导出 PNG，应用照样启动，只是窗口左上角
 * 变成一个默认咖啡杯。这里用像素断言把「圆角透明、主色蓝底、白封面、厚度缝、底部书页」
 * 五件事钉住，设计改动必须同步断言（改 {@code make-icon.py} 时一起改本类）。
 *
 * <p>与 {@code theme.css} 的关系：底色就是 {@code -epubra-accent}，
 * 书体是 {@code -epubra-on-accent-fg}——图标跟着主题变量走，不额外发明配色。
 */
class AppIconTest {

    /** {@code -epubra-accent} = #1a5fb4（theme.css 浅色主题值）。 */
    private static final Color ACCENT = Color.rgb(0x1a, 0x5f, 0xb4);
    /** {@code -epubra-on-accent-fg} = #ffffff。 */
    private static final Color PAPER = Color.rgb(0xff, 0xff, 0xff);

    private static final List<Double> EXPECTED_SIZES =
            List.of(16.0, 32.0, 48.0, 64.0, 128.0, 256.0, 512.0);

    @Test
    @DisplayName("七个尺寸全部能加载，尺寸与 make-icon.py 的 SIZES 一致")
    void allIconSizesLoad() {
        List<Image> icons = EpubraApp.loadIcons();

        assertEquals(EXPECTED_SIZES, icons.stream().map(Image::getWidth).toList(),
                "图标尺寸集合应与 tools/make-icon.py 的 SIZES 一致——漏一个就是任务栏/Alt+Tab 拿到糊图");
        assertFalse(icons.stream().anyMatch(Image::isError), "图标不应有加载错误");
    }

    @Test
    @DisplayName("512px 图标的图形内容：圆角透明、主色蓝底、白封面、书脊缝、厚度缝、底部书页")
    void iconGeometryIsDrawnCorrectly() {
        // 用 512px（最接近 2048 设计基准）而不是 32px 做几何断言：书脊缝只占边长 1.8%，
        // 32px 下宽 0.58px、128px 下宽 2.3px，中心像素都被 LANCZOS 振铃污染
        //（实测 128px 缝中心拿到 0x004cabff，比主色更暗，是 undershoot 而非真实配色）。
        // 512px 下缝宽 9.2px，中心像素才是干净的主色。
        Image icon = EpubraApp.loadIcons().stream()
                .filter(image -> image.getWidth() == 512)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 512px 图标"));

        // 四角在圆角之外：留一点容差，下采样会在圆角边缘渗出几个半透明像素
        assertTrue(alphaAt(icon, 0, 0) < 24, "左上角应在圆角外（接近透明）");
        assertTrue(alphaAt(icon, 511, 511) < 24, "右下角应在圆角外（接近透明）");

        // 底色铺满上下边中点
        assertColor(icon, 256, 0, ACCENT, "顶边中点应为 -epubra-accent");
        assertColor(icon, 256, 511, ACCENT, "底边中点应为 -epubra-accent");

        assertColor(icon, 256, 256, PAPER, "封面应为 -epubra-on-accent-fg");
        assertColor(icon, 181, 256, ACCENT, "封面左侧的书脊缝应露出底色");
        assertColor(icon, 256, 366, ACCENT, "封面与书页之间的缝应露出底色（读作书的厚度）");
        assertColor(icon, 256, 393, PAPER, "底部书页应为白色");
    }

    @Test
    @DisplayName("打包资产 epubra.ico 必须一起存在（jpackage 用）")
    void icoAssetIsPackaged() {
        assertNotNull(EpubraApp.class.getResource("/org/chobit/epubra/app/icon/epubra.ico"),
                "多尺寸 ICO 缺失：将来 jpackage / exe 打包会退回默认图标");
    }

    // ---- 工具 ----

    private static int alphaAt(Image image, int x, int y) {
        return (int) Math.round(image.getPixelReader().getColor(x, y).getOpacity() * 255);
    }

    /** 颜色比较带容差：下采样后的边缘像素会有抗锯齿混色，但纯色区域中心应是精确值。 */
    private static void assertColor(Image image, int x, int y, Color expected, String what) {
        Color actual = image.getPixelReader().getColor(x, y);
        double delta = Math.max(Math.max(
                        Math.abs(actual.getRed() - expected.getRed()),
                        Math.abs(actual.getGreen() - expected.getGreen())),
                Math.abs(actual.getBlue() - expected.getBlue()));
        assertTrue(delta < 0.02,
                what + "：取 (" + x + "," + y + ") 实得 " + actual + "，期望 " + expected);
    }
}
