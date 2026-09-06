package org.chobit.epubra.lib.domain;

/**
 * EPUB 常用媒体类型常量。
 */
public final class MediaTypes {

    public static final String XHTML = "application/xhtml+xml";
    public static final String NCX = "application/x-dtbncx+xml";
    public static final String OPF = "application/oebps-package+xml";
    public static final String CSS = "text/css";
    public static final String SVG = "image/svg+xml";

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String GIF = "image/gif";
    public static final String WEBP = "image/webp";

    public static final String TTF = "font/ttf";
    public static final String OTF = "font/otf";
    public static final String WOFF = "font/woff";
    public static final String WOFF2 = "font/woff2";

    /** EPUB 容器内 nav 文档使用的媒体类型。 */
    public static final String NAV_DOCUMENT = XHTML;

    private MediaTypes() {
    }

    /** 依据文件扩展名猜测媒体类型，无法识别时回退为 {@code application/octet-stream}。 */
    public static String guessByExtension(String href) {
        if (href == null) {
            return "application/octet-stream";
        }
        int dot = href.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return switch (href.substring(dot + 1).toLowerCase()) {
            case "xhtml", "html", "htm" -> XHTML;
            case "ncx" -> NCX;
            case "opf" -> OPF;
            case "css" -> CSS;
            case "svg" -> SVG;
            case "jpg", "jpeg" -> JPEG;
            case "png" -> PNG;
            case "gif" -> GIF;
            case "webp" -> WEBP;
            case "ttf" -> TTF;
            case "otf" -> OTF;
            case "woff" -> WOFF;
            case "woff2" -> WOFF2;
            default -> "application/octet-stream";
        };
    }
}
