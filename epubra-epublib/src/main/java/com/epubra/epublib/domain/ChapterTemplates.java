package com.epubra.epublib.domain;

/**
 * 章节 XHTML 模板生成。
 */
public final class ChapterTemplates {

    /** XHTML 文本中的 XML 特殊字符转义。 */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** 生成一个最小可用的章节文档。 */
    public static String empty(String title) {
        String safeTitle = title == null || title.isBlank() ? "新章节" : title.trim();
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                  <title>%s</title>
                </head>
                <body>
                  <h1>%s</h1>
                  <p>在此输入正文内容。</p>
                </body>
                </html>
                """.formatted(escape(safeTitle), escape(safeTitle));
    }

    /** 从章节 XHTML 中抽取 {@code <title>} 或首个 h1~h3 的文本。 */
    public static String extractTitle(String xhtml) {
        if (xhtml == null || xhtml.isBlank()) {
            return "无标题";
        }
        String title = extractFirst(xhtml, "title");
        if (title != null) {
            return title;
        }
        for (String tag : new String[]{"h1", "h2", "h3"}) {
            String heading = extractFirst(xhtml, tag);
            if (heading != null) {
                return heading;
            }
        }
        return "无标题";
    }

    private static String extractFirst(String xhtml, String tag) {
        String open = "<" + tag;
        int start = xhtml.toLowerCase().indexOf(open);
        if (start < 0) {
            return null;
        }
        int gt = xhtml.indexOf('>', start);
        int close = xhtml.toLowerCase().indexOf("</" + tag + ">", gt);
        if (gt < 0 || close < 0) {
            return null;
        }
        String raw = xhtml.substring(gt + 1, close).trim();
        String plain = raw.replaceAll("<[^>]+>", "").trim();
        return plain.isBlank() ? null : unescape(plain);
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private ChapterTemplates() {
    }
}
