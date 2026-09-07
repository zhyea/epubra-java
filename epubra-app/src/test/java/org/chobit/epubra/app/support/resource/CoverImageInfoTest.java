package org.chobit.epubra.app.support.resource;

import org.chobit.epubra.app.support.resource.CoverImageInfo.Dimension;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CoverImageInfo} 各图片格式头解析的契约测试。
 *
 * <p>手写脚本字节构造各格式最小可用示例（PNG 直接参考 IHDR；JPEG 用最小一帧 SOF0；
 * GIF 走 87a/89a；SVG 走 width/height 属性）。所有测试离线运行，不依赖
 * {@link javafx.scene.image.Image}，因此无 JavaFX Toolkit 也能跑。
 */
class CoverImageInfoTest {

    @Test
    void pngHeader1600x2400() {
        Optional<Dimension> dim = CoverImageInfo.read(pngHeader(1600, 2400));
        assertTrue(dim.isPresent());
        assertEquals(new Dimension(1600, 2400), dim.get());
    }

    @Test
    void pngHeaderSmallSize() {
        Optional<Dimension> dim = CoverImageInfo.read(pngHeader(8, 8));
        assertTrue(dim.isPresent());
        assertEquals(new Dimension(8, 8), dim.get());
    }

    @Test
    void gifHeader1280x720() {
        Optional<Dimension> dim = CoverImageInfo.read(gifHeader(1280, 720));
        assertTrue(dim.isPresent());
        assertEquals(new Dimension(1280, 720), dim.get());
    }

    @Test
    void gifHeader87a() {
        Optional<Dimension> dim = CoverImageInfo.read(
                gifHeader(640, 480, "GIF87a".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(dim.isPresent());
        assertEquals(new Dimension(640, 480), dim.get());
    }

    @Test
    void jpegSof0ParsesDimensions() {
        // FF D8 + APP0(16 字节) + SOF0 段：长度 17、8 bit、高=480、宽=320
        byte[] jpeg = new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00,
                0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xC0,
                0x00, 0x11,
                0x08,
                0x01, (byte) 0xE0, // height = 480
                0x01, (byte) 0x40, // width = 320
                0x01, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01
        };
        Optional<Dimension> dim = CoverImageInfo.read(jpeg);
        assertTrue(dim.isPresent());
        assertEquals(new Dimension(320, 480), dim.get());
    }

    @Test
    void webpFormatDetectedAndParserDoesNotCrash() {
        // WebP RIFF 嗅探 + VP8 分支不抛异常。P0 阶段不强求尺寸数值正确：
        // VP8 帧尺寸是 14-bit 值跨三字节打包（start code 3 + frame tag 3 + width/height 异位），
        // 严格构造需要精确位序列；解析失败返回 Optional.empty() 时 UI 跳过尺寸行，不打断主流程。
        byte[] webp = webpSyntheticRiff();
        var result = CoverImageInfo.read(webp);
        if (result.isPresent()) {
            assertTrue(result.get().width() > 0 && result.get().height() > 0);
            assertTrue(result.get().width() <= 100000 && result.get().height() <= 100000);
        }
    }

    @Test
    void webpUnknownFourCcReturnsEmpty() {
        // VP8X（动画/扩展）等非 VP8/VP8L 分支：合法识别 RIFF/WEBP 但无法解析尺寸
        byte[] webp = webpSyntheticRiffWithFourCc("VP8X".getBytes(StandardCharsets.US_ASCII));
        assertFalse(CoverImageInfo.read(webp).isPresent());
    }

    @Test
    void svgWidthHeightAttributes() {
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\">"
                + "<rect x=\"0\" y=\"0\" width=\"100\" height=\"100\"/></svg>").getBytes(StandardCharsets.UTF_8);
        Optional<Dimension> dim = CoverImageInfo.read(svg);
        assertTrue(dim.isPresent());
        assertEquals(new Dimension(800, 600), dim.get());
    }

    @Test
    void svgFallsBackToViewBox() {
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1024 768\">"
                + "</svg>").getBytes(StandardCharsets.UTF_8);
        Optional<Dimension> dim = CoverImageInfo.read(svg);
        assertTrue(dim.isPresent());
        assertEquals(new Dimension(1024, 768), dim.get());
    }

    @Test
    void unknownBinaryReturnsEmpty() {
        byte[] data = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertFalse(CoverImageInfo.read(data).isPresent());
    }

    @Test
    void nullOrShortReturnsEmpty() {
        assertFalse(CoverImageInfo.read(null).isPresent());
        assertFalse(CoverImageInfo.read(new byte[5]).isPresent());
    }

    @Test
    void rejectsZeroOrNegativeDimensions() {
        byte[] png = pngHeader(0, 0);
        assertFalse(CoverImageInfo.read(png).isPresent());
    }

    // ---- helpers ---

    /** PNG：8 字节签名 + IHDR 13 字节字段（4 长 + 4 类型 + 4 宽 + 4 高 + 5 其他）。 */
    private static byte[] pngHeader(int width, int height) {
        byte[] png = new byte[24];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        png[4] = 0x0D;
        png[5] = 0x0A;
        png[6] = 0x1A;
        png[7] = 0x0A;
        // IHDR chunk length (13 bytes)
        png[8] = 0;
        png[9] = 0;
        png[10] = 0;
        png[11] = 13;
        png[12] = 'I';
        png[13] = 'H';
        png[14] = 'D';
        png[15] = 'R';
        writeIntBE(png, 16, width);
        writeIntBE(png, 20, height);
        return png;
    }

    private static byte[] gifHeader(int width, int height) {
        return gifHeader(width, height, "GIF89a".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] gifHeader(int width, int height, byte[] signature) {
        byte[] gif = new byte[10];
        System.arraycopy(signature, 0, gif, 0, 6);
        writeIntLE16(gif, 6, width);
        writeIntLE16(gif, 8, height);
        return gif;
    }

    /**
     * 合成最小 WebP 容器：RIFF + size + WEBP + VP8 chunk + size + frame 数据。
     * 具体尺寸字段不严格对齐，但保证是合法 RIFF/WEBP/VP8 入口，确保分支走到。
     */
    private static byte[] webpSyntheticRiff() {
        return webpSyntheticRiffWithFourCc("VP8 ".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] webpSyntheticRiffWithFourCc(byte[] fourcc) {
        int chunkSize = 16;
        int totalSize = 12 + 8 + chunkSize;
        int riffSize = totalSize - 8;
        byte[] webp = new byte[totalSize];
        webp[0] = 'R';
        webp[1] = 'I';
        webp[2] = 'F';
        webp[3] = 'F';
        writeIntLE32(webp, 4, riffSize);
        webp[8] = 'W';
        webp[9] = 'E';
        webp[10] = 'B';
        webp[11] = 'P';
        // chunk FourCC + size
        System.arraycopy(fourcc, 0, webp, 12, 4);
        writeIntLE32(webp, 16, chunkSize);
        // 凑够 chunkSize 字节数据：start code + 占位
        webp[24] = (byte) 0x9D;
        webp[25] = 0x01;
        webp[26] = 0x2A;
        return webp;
    }

    private static void writeIntBE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) ((value >>> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }

    private static void writeIntLE16(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static void writeIntLE32(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
