package org.chobit.epubra.app.editor;

import org.chobit.epubra.lib.domain.ChapterTemplates;

/**
 * 正文查找替换与章节标题同步用到的纯文本工具。
 *
 * <p>与界面无关，单独成类以便在无 JavaFX 环境下做单元测试。
 */
public final class TextSearch {

    /** 一次全部替换的结果。 */
    public record ReplaceResult(String text, int count) {
    }

    private TextSearch() {
    }

    /** 从 from 起向后查找，未找到返回 -1。 */
    public static int indexOf(String text, String keyword, int from, boolean caseSensitive) {
        if (text == null || keyword == null || keyword.isEmpty()) {
            return -1;
        }
        if (caseSensitive) {
            return text.indexOf(keyword, Math.min(from, text.length()));
        }
        return text.toLowerCase().indexOf(keyword.toLowerCase(), Math.min(from, text.length()));
    }

    /** 从 from 起向前查找（from 为起始的最大下标），未找到返回 -1。 */
    public static int lastIndexOf(String text, String keyword, int from, boolean caseSensitive) {
        if (text == null || keyword == null || keyword.isEmpty() || from < 0) {
            return -1;
        }
        if (caseSensitive) {
            return text.lastIndexOf(keyword, Math.min(from, text.length() - 1));
        }
        return text.toLowerCase().lastIndexOf(keyword.toLowerCase(), Math.min(from, text.length() - 1));
    }

    /** 选中的文本是否就是待查找的关键词。 */
    public static boolean matches(String selected, String keyword, boolean caseSensitive) {
        if (selected == null || selected.isEmpty() || keyword == null || keyword.isEmpty()) {
            return false;
        }
        return caseSensitive ? selected.equals(keyword) : selected.equalsIgnoreCase(keyword);
    }

    /** 替换全部出现位置；无匹配时原样返回且 count 为 0。 */
    public static ReplaceResult replaceAll(String text, String keyword, String replacement,
                                           boolean caseSensitive) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) {
            return new ReplaceResult(text, 0);
        }
        String haystack = caseSensitive ? text : text.toLowerCase();
        String needle = caseSensitive ? keyword : keyword.toLowerCase();
        StringBuilder out = new StringBuilder();
        int count = 0;
        int cursor = 0;
        while (true) {
            int index = haystack.indexOf(needle, cursor);
            if (index < 0) {
                break;
            }
            out.append(text, cursor, index).append(replacement);
            cursor = index + needle.length();
            count++;
        }
        if (count == 0) {
            return new ReplaceResult(text, 0);
        }
        out.append(text, cursor, text.length());
        return new ReplaceResult(out.toString(), count);
    }

    /**
     * 粗略统计 XHTML 的字符数（状态栏「字数」用）。
     *
     * <p>先剥掉 script / style 块、注释与标签，再把实体替换成单个占位字符，
     * 最后只数非空白字符。JS 与 CSS 的内容不计入，实体按一个字算。
     */
    public static int plainTextLength(String xhtml) {
        if (xhtml == null || xhtml.isEmpty()) {
            return 0;
        }
        String text = xhtml;
        text = text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?s)<!--.*?-->", " ");
        text = text.replaceAll("(?s)<[^>]*>", " ");
        text = text.replaceAll("&[a-zA-Z][a-zA-Z0-9]*;|&#[0-9]+;|&#x[0-9a-fA-F]+;", " ");
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 定位 {@code id="value"} 或 {@code id='value'} 中 value 的起始下标；未找到返回 -1。
     *
     * <p>用于把「锚点不存在」这类问题定位到正文中具体的元素上。
     */
    public static int indexOfIdAttribute(String xhtml, String id) {
        if (xhtml == null || xhtml.isEmpty() || id == null || id.isEmpty()) {
            return -1;
        }
        // 值前面固定是 id= 与一个引号，共 4 个字符
        int best = -1;
        for (String quote : new String[]{"\"", "'"}) {
            int index = indexOf(xhtml, "id=" + quote + id + quote, 0, false);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index + "id=".length() + quote.length();
            }
        }
        return best;
    }

    /**
     * {@link #locateAnchor} 的结果：匹配起始下标 + <b>实际匹配的长度</b>。
     *
     * <p>长度必须跟着「用的是哪个 needle」走，不能由调用方拿锚点原文长度代替——
     * 末段匹配（{@code text/ch1.xhtml#sec2} 退化成 {@code ch1.xhtml#sec2}）时整串长度会偏大，
     * 按它选中会越过匹配文本、多高亮一截。
     */
    public record AnchorMatch(int index, int length) {

        public int end() {
            return index + length;
        }
    }

    /**
     * 在正文里定位一个引用锚点；返回匹配位置与实际长度，未找到返回 null。
     *
     * <p>三级降级，与「问题面板」定位的既有语义一致：
     * <ol>
     *   <li>纯片段锚点（{@code #id}）优先按 {@code id="…"} 属性定位；</li>
     *   <li>否则按锚点原串整体搜；</li>
     *   <li>再退化为最后一个 {@code /} 之后的末段（正文里不会出现路径，但会出现文件名）。</li>
     * </ol>
     *
     * @param xhtml    正文字符串
     * @param anchor   引用锚点（href 或 href#fragment）
     * @param fragment 锚点是否为纯片段——是则优先走 {@link #indexOfIdAttribute}
     */
    public static AnchorMatch locateAnchor(String xhtml, String anchor, boolean fragment) {
        if (xhtml == null || xhtml.isEmpty() || anchor == null || anchor.isEmpty()) {
            return null;
        }
        if (fragment) {
            int idAt = indexOfIdAttribute(xhtml, anchor);
            if (idAt >= 0) {
                return new AnchorMatch(idAt, anchor.length());
            }
        }
        int at = indexOf(xhtml, anchor, 0, true);
        if (at >= 0) {
            return new AnchorMatch(at, anchor.length());
        }
        int slash = anchor.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < anchor.length()) {
            String tail = anchor.substring(slash + 1);
            int tailAt = indexOf(xhtml, tail, 0, true);
            if (tailAt >= 0) {
                return new AnchorMatch(tailAt, tail.length());
            }
        }
        return null;
    }

    /**
     * 把 XHTML 中第一个指定标签的正文替换为新文本，用于重命名章节时同步 {@code <title>} 与 {@code <h1>}。
     *
     * <p>标签不存在或不成对时原样返回，避免破坏用户手写的正文。
     */
    public static String replaceFirstTagText(String xhtml, String tag, String newText) {
        if (xhtml == null || tag == null || tag.isEmpty()) {
            return xhtml;
        }
        String lower = xhtml.toLowerCase();
        int start = lower.indexOf("<" + tag);
        if (start < 0) {
            return xhtml;
        }
        int openEnd = xhtml.indexOf('>', start);
        if (openEnd < 0) {
            return xhtml;
        }
        int closeStart = lower.indexOf("</" + tag + ">", openEnd);
        if (closeStart < 0) {
            return xhtml;
        }
        return xhtml.substring(0, openEnd + 1) + ChapterTemplates.escape(newText) + xhtml.substring(closeStart);
    }
}
