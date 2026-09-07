package org.chobit.epubra.app.ui.support.resource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 从图片字节流读取宽高（用于封面卡显示「1600 × 2400」之类的尺寸信息）。
 *
 * <p><b>不用 {@code javafx.scene.image.Image}</b>：Image 依赖 graphics 模块且与 toolkit
 * 生命周期耦合，无头单测不可靠。PNG / JPEG / GIF 头解析是几十行定长字节读取，可
 * 100% 单测覆盖；WebP 与 SVG 暂不解析（按"未知"对待，UI 跳过尺寸那一行）。
 *
 * <p>所有方法均返回 {@link Optional}，找不到合法头 / 字节流太短 返回 {@code Optional.empty()}，
 * 让 UI 层用 {@code ifPresent(...)} 决定要不要显示尺寸这一行——避免抛异常打断加载流程。
 */
public final class CoverImageInfo {

    private CoverImageInfo() {
    }

    /** 宽高结果。不可变 record，{@code null} 表示未知。 */
    public record Dimension(int width, int height) {
    }

    /**
     * 自动按字节前几个字节嗅探格式并解析尺寸。识别失败（含 WebP / SVG）回退到
     * {@link Optional#empty()}。
     */
    public static Optional<Dimension> read(byte[] data) {
        if (data == null || data.length < 2) {
            return Optional.empty();
        }
        if (isPng(data)) {
            return readPng(data);
        }
        if (isJpeg(data)) {
            return readJpeg(data);
        }
        if (isGif(data)) {
            return readGif(data);
        }
        if (isWebp(data)) {
            return readWebp(data);
        }
        if (isSvg(data)) {
            return readSvg(data);
        }
        return Optional.empty();
    }

    // --------------------------------------------------------------- PNG

    /** PNG 签名 8 字节：89 50 4E 47 0D 0A 1A 0A。 */
    private static boolean isPng(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xff) == 0x89
                && data[1] == 'P'
                && data[2] == 'N'
                && data[3] == 'G'
                && data[4] == 0x0D
                && data[5] == 0x0A
                && data[6] == 0x1A
                && data[7] == 0x0A;
    }

    /** IHDR 块：4 字节长度 + "IHDR" + 4 字节宽 + 4 字节高（大端）。总 24 字节。 */
    private static Optional<Dimension> readPng(byte[] data) {
        if (data.length < 24) {
            return Optional.empty();
        }
        int width = readIntBE(data, 16);
        int height = readIntBE(data, 20);
        return sanitize(width, height);
    }

    // --------------------------------------------------------------- JPEG

    /** JPEG SOI 标记：FF D8。 */
    private static boolean isJpeg(byte[] data) {
        return data.length >= 2 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xD8;
    }

    /**
     * 顺次扫描 JPEG 段直到找到 SOFn：SOF0..SOF3、SOF5..SOF7、SOF9..SOF11、SOF13..SOF15
     * （排除 DHT=C4、DAC=CC、JPG=C8 这三个含尺寸但用途非帧定义的标记）。
     */
    private static Optional<Dimension> readJpeg(byte[] data) {
        int i = 2; // skip SOI
        while (i + 1 < data.length) {
            // 期望下一字节是 0xFF（标记前缀），允许任意多个 0xFF 填充
            while (i < data.length && (data[i] & 0xff) != 0xFF) {
                i++;
            }
            if (i + 1 >= data.length) {
                return Optional.empty();
            }
            int marker = data[i + 1] & 0xff;
            i += 2;
            if (marker == 0xFF || marker == 0x00 || marker == 0xD8 || marker == 0xD9) {
                // 填充字节 / SOI / EOI：跳过 0xFF 填充循环；此处 marker==0x00 或 0xD8 不合法，继续走
                continue;
            }
            // SOFn 群：C0-C3（DHT/C4、C8/JPG、CC/DAC 排除），C5-C7，C9-CB，CD-CF
            boolean isSof = (marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC);
            if (!isSof) {
                // 普通段（含 APPn / DQT / COM / DRI …）：长度 2 字节，跳过
                if (i + 1 >= data.length) {
                    return Optional.empty();
                }
                int segLen = readIntBE16(data, i);
                if (segLen < 2 || i + segLen > data.length) {
                    return Optional.empty();
                }
                i += segLen;
                continue;
            }
            // SOFn 内容：长度(2) + 精度(1) + 高(2) + 宽(2)
            if (i + 7 > data.length) {
                return Optional.empty();
            }
            int height = readIntBE16(data, i + 3);
            int width = readIntBE16(data, i + 5);
            return sanitize(width, height);
        }
        return Optional.empty();
    }

    // --------------------------------------------------------------- GIF

    private static boolean isGif(byte[] data) {
        if (data.length < 10) {
            return false;
        }
        // "GIF87a" / "GIF89a"
        return data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a';
    }

    private static Optional<Dimension> readGif(byte[] data) {
        int width = readIntLE16(data, 6);
        int height = readIntLE16(data, 8);
        return sanitize(width, height);
    }

    // --------------------------------------------------------------- WebP

    /**
     * WebP RIFF 容器：'RIFF' + size + 'WEBP' + chunks。
     * 仅解析 VP8 / VP8L 简易场景；VP8X（扩展 / 动画）跳过——多数封面图走 VP8 静态。
     */
    private static boolean isWebp(byte[] data) {
        if (data.length < 12) {
            return false;
        }
        return data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private static Optional<Dimension> readWebp(byte[] data) {
        if (data.length < 30) {
            return Optional.empty();
        }
        // 从 offset 12 起 chunks：FourCC(4) + size(4 LE)
        int chunkStart = 12;
        if (chunkStart + 8 > data.length) {
            return Optional.empty();
        }
        String fourcc = new String(data, chunkStart, 4, StandardCharsets.US_ASCII);
        int size = readIntLE32(data, chunkStart + 4);
        if ("VP8 ".equals(fourcc)) {
            // VP8 lossy：size 之后 3 字节 start code (0x9D 0x01 0x2A) + 2 字节宽 + 2 字节高（小端，
            // 14-bit 编码：高 2 bit 在 width 第 15 位、低 2 bit 在 height 的高位）
            int p = chunkStart + 8;
            if (p + 10 > data.length) {
                return Optional.empty();
            }
            boolean isKeyFrame = (data[p] & 0xff) == 0x9D
                    && (data[p + 1] & 0xff) == 0x01
                    && (data[p + 2] & 0xff) == 0x2A;
            if (!isKeyFrame) {
                return Optional.empty();
            }
            int b0 = data[p + 3] & 0xff;
            int b1 = data[p + 4] & 0xff;
            int b2 = data[p + 5] & 0xff;
            int width = b0 | ((b1 & 0x3F) << 8);
            int height = ((b1 & 0xC0) >>> 6) | (b2 << 2);
            return sanitize(width, height);
        }
        if ("VP8L".equals(fourcc)) {
            // VP8 lossless：1 字节 signature (0x2F) + 4 字节宽高混合字段
            int p = chunkStart + 8;
            if (p + 5 > data.length) {
                return Optional.empty();
            }
            if ((data[p] & 0xff) != 0x2F) {
                return Optional.empty();
            }
            int b1 = data[p + 1] & 0xff;
            int b2 = data[p + 2] & 0xff;
            int b3 = data[p + 3] & 0xff;
            int b4 = data[p + 4] & 0xff;
            int width = ((b2 & 0x3F) << 8) | b1;
            int height = ((b4 & 0x0F) << 10) | (b3 << 2) | ((b2 & 0xC0) >>> 6);
            return sanitize(width, height);
        }
        // VP8X（动画/扩展）或其它：放弃解析
        return Optional.empty();
    }

    // --------------------------------------------------------------- SVG

    /**
     * SVG 嗅探：从字节流里找前 256 个 ASCII 字符里是否含 {@code <svg}（允许大小写、属性、空白），
     * 找到后再正则抽 {@code width} / {@code height} / {@code viewBox}。仅取前段，避免对
     * 嵌入大量图像数据的大 SVG 全量扫描。
     */
    private static boolean isSvg(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 256), StandardCharsets.US_ASCII);
        return head.contains("<svg");
    }

    private static Optional<Dimension> readSvg(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.UTF_8);
        java.util.regex.Matcher width = WIDTH.matcher(head);
        java.util.regex.Matcher height = HEIGHT.matcher(head);
        if (width.find() && height.find()) {
            try {
                int w = Integer.parseInt(width.group(1));
                int h = Integer.parseInt(height.group(1));
                return sanitize(w, h);
            } catch (NumberFormatException ignored) {
                // fallthrough to viewBox
            }
        }
        java.util.regex.Matcher vb = VIEWBOX.matcher(head);
        if (vb.find()) {
            try {
                int w = Integer.parseInt(vb.group(3));
                int h = Integer.parseInt(vb.group(4));
                return sanitize(w, h);
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return Optional.empty();
    }

    private static final java.util.regex.Pattern WIDTH = java.util.regex.Pattern.compile(
            "<svg[^>]*\\swidth\\s*=\\s*\"?(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern HEIGHT = java.util.regex.Pattern.compile(
            "<svg[^>]*\\sheight\\s*=\\s*\"?(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern VIEWBOX = java.util.regex.Pattern.compile(
            "<svg[^>]*\\sviewBox\\s*=\\s*\"([\\d.\\-]+)\\s+([\\d.\\-]+)\\s+(\\d+)\\s+(\\d+)\"",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    // --------------------------------------------------------------- helpers

    private static Optional<Dimension> sanitize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }
        // 上限避免畸形文件触发后续控件异常
        if (width > 100000 || height > 100000) {
            return Optional.empty();
        }
        return Optional.of(new Dimension(width, height));
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static int readIntBE16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8)
                | (data[offset + 1] & 0xff);
    }

    private static int readIntLE16(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8);
    }

    private static int readIntLE32(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }
}
