package org.chobit.epubra.app.editor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 给预览用的章节 XHTML 注入主题样式。
 *
 * <p>WebView 里的内容是作者自己写的 XHTML，配色由文档自身的内联样式决定，JavaFX 的
 * {@code -epubra-*} 变量传不进去；深色主题下若不处理，预览区会仍是白底黑字，与周围
 * 深灰界面反差极大。这里往 {@code <head>} 里追加一段带 {@code !important} 的内联样式，
 * 压过文档自带的配色。
 *
 * <p>纯字符串处理，不依赖 JavaFX 运行时，可单测。
 */
public final class PreviewHtml {

    private static final String XHTML_NS = "http://www.w3.org/1999/xhtml";

    /** 注入样式与编辑脚本共用的标识：回写序列化时靠它把预览样式剥掉。 */
    static final String INJECTED_STYLE_ID = "epubra-preview-style";

    /**
     * 注入的 {@code <base>} 的元素 id。
     *
     * <p>可视化编辑器回写正文时同样要把它剥掉——镜像目录是运行期临时产物，
     * 一旦写进书里就成了指向 {@code ~/.Epubra/} 的绝对路径，换台机器整章图片全断。
     */
    static final String INJECTED_BASE_ID = "epubra-preview-base";

    /**
     * 注入的编辑脚本元素 id。
     *
     * <p>脚本比样式 / 基准更必须在回写序列化时剥掉——它有几百行，一旦写进章节，
     * 源码视图与整本书都会被无意义 JS 污染。样式与基准一直带 id，脚本却曾经没有，
     * 于是每次可视化编辑的回写都把整段脚本带进正文（#51）。
     */
    static final String INJECTED_SCRIPT_ID = "epubra-preview-script";

    private PreviewHtml() {
    }

    /**
     * 注入主题样式。
     *
     * <p>按文档结构的完整程度依次降级：有 {@code </head>} 就插在它前面；只有
     * {@code <body>} 就补一个 {@code <head>}；只有 {@code <html>} 就插在 html 开标签之后；
     * 连根标签都没有的裸片段则整体包一层最小文档。
     *
     * @param xhtml 章节正文；为 null 或全空白时返回 {@link #emptyDocument(Theme)}
     * @param theme 当前主题
     */
    public static String withTheme(String xhtml, Theme theme) {
        return injectStyle(xhtml, styleTag(theme));
    }

    /**
     * 把一段 {@code <style>} 注入 {@code <head>}。
     *
     * <p>按文档结构的完整程度依次降级：有 {@code </head>} 就插在它前面；只有
     * {@code <body>} 就补一个 {@code <head>}；只有 {@code <html>} 就插在 html 开标签之后；
     * 连根标签都没有的裸片段则整体包一层最小文档。
     */
    private static String injectStyle(String xhtml, String style) {
        if (xhtml == null || xhtml.isBlank()) {
            return "<html xmlns=\"" + XHTML_NS + "\"><head>" + style + "</head><body></body></html>";
        }
        int headClose = indexOfIgnoringCase(xhtml, "</head>");
        if (headClose >= 0) {
            return xhtml.substring(0, headClose) + style + "\n" + xhtml.substring(headClose);
        }
        int bodyStart = indexOfIgnoringCase(xhtml, "<body");
        if (bodyStart >= 0) {
            return xhtml.substring(0, bodyStart) + "<head>" + style + "</head>"
                    + xhtml.substring(bodyStart);
        }
        int htmlTagEnd = endOfOpenTag(xhtml, "<html");
        if (htmlTagEnd >= 0) {
            return xhtml.substring(0, htmlTagEnd) + "<head>" + style + "</head>"
                    + xhtml.substring(htmlTagEnd);
        }
        return "<html xmlns=\"" + XHTML_NS + "\"><head>" + style + "</head><body>"
                + xhtml + "</body></html>";
    }

    /** 无章节时的空预览文档；背景跟随主题，避免出现一张刺眼的空白页。 */
    public static String emptyDocument(Theme theme) {
        return "<html xmlns=\"" + XHTML_NS + "\"><head>" + styleTag(theme) + "</head><body></body></html>";
    }

    /**
     * 注入解析基准：{@code <base href="…"/>}。
     *
     * <p>预览与可视化编辑器都走 {@code loadContent}，页面源是 {@code about:blank}，正文里
     * {@code <img src="../images/a.png"/>} 这类相对引用没有基准可依附，一律加载不出来。
     * 把基准指到资源镜像里当前章节的目录即可正常显示。
     *
     * <p>相比把 {@code src} 换成 {@code data:} URI，{@code <base>} 只影响解析、不改
     * {@code src} 的属性值，因此可视化编辑器回写正文时零影响。
     *
     * <p>插在 {@code <head>} 开标签之后而不是 {@code </head>} 之前：HTML 规定
     * {@code <base>} 必须先于其他引用 URL 的元素生效，排在 {@code <link>} 前面最稳妥。
     * 文档没有 head 时退化为插在 {@code </head>} 之前；都没有则原样返回（无处可插）。
     *
     * @param xhtml    章节正文
     * @param baseHref 基准地址（通常是 {@code file:} URI，以 {@code /} 结尾）；空则原样返回
     */
    public static String withBaseHref(String xhtml, String baseHref) {
        if (xhtml == null || xhtml.isBlank() || baseHref == null || baseHref.isBlank()) {
            return xhtml;
        }
        String tag = "<base id=\"" + INJECTED_BASE_ID + "\" href=\"" + escapeAttribute(baseHref) + "\"/>";
        int headOpen = endOfHeadOpenTag(xhtml);
        if (headOpen >= 0) {
            return xhtml.substring(0, headOpen) + tag + xhtml.substring(headOpen);
        }
        int headClose = indexOfIgnoringCase(xhtml, "</head>");
        if (headClose >= 0) {
            return xhtml.substring(0, headClose) + tag + xhtml.substring(headClose);
        }
        return xhtml;
    }

    /**
     * 可视化编辑文档：在 {@link #withTheme(String, Theme)} 的基础上把 {@code <body>} 置为
     * {@code contenteditable}，并挂一段编辑脚本。
     *
     * <p><b>为什么走 WebView 而不是 {@code HTMLEditor}</b>：WebView 以
     * {@code application/xhtml+xml} 加载时 DOM 就是 XML DOM，回写时用 {@code XMLSerializer}
     * 序列化天然得到合法 XHTML（空元素自闭合）；{@code HTMLEditor} 产的是 HTML
     * （{@code <br>} 非自闭合 + 行内 style），写回正文前还得自写 HTML→XHTML 转换。
     *
     * <p>脚本通过 {@code window.epubraBridge} 与 Java 双向通信：
     * <ul>
     *   <li>{@code onEdited(xhtml)} —— 编辑结果回写（input 600ms 节流 + blur 主动推）</li>
     *   <li>{@code onSelectionChanged(formats)} —— 光标处生效的格式名，供工具条点亮</li>
     *   <li>{@code onUndo()} / {@code onRedo()} —— Ctrl+Z / Ctrl+Y 交给应用的快照撤销</li>
     * </ul>
     * Java 侧可调用的入口：
     * <ul>
     *   <li>{@code window.epubraFormat(kind[, value])} —— 段落/标题/引用/列表/编号列表/
     *       分隔线/加粗/斜体/下划线/删除线/行内代码/链接/取消链接。行内格式与列表均为
     *       切换语义：已生效再调一次即取消；列表内 Tab / Shift+Tab 调层级</li>
     *   <li>{@code window.epubraInsertHtml(html)} —— 片段插到光标处（图片等）</li>
     *   <li>{@code window.epubraQuery()} —— 当前生效格式名（空格分隔）</li>
     *   <li>{@code window.epubraQueryLink()} —— 光标所在链接的 href（不在链接内为空串）</li>
     *   <li>{@code window.epubraSerialize()} —— 主动拉取当前正文</li>
     * </ul>
     *
     * <p>所有命令都是手工 Range/DOM 操作，<b>不用 {@code document.execCommand}</b>：后者
     * 产出的标签随引擎而异（{@code <b>} / {@code <span style>}），而回写正文必须是确定的
     * XHTML。粘贴走白名单净化（剥掉 script/事件属性/内联样式，{@code b} 归并成
     * {@code strong}），避免外部富文本污染正文。
     */
    public static String editableDocument(String xhtml, Theme theme) {
        return editableDocument(xhtml, theme, null);
    }

    /**
     * 可视化编辑文档 + 解析基准：在 {@link #editableDocument(String, Theme)} 基础上注入
     * {@link #withBaseHref(String, String)} 的 {@code <base>}，让正文里的相对图片引用能显示。
     *
     * <p>注入的 {@code <base>} 带 {@link #INJECTED_BASE_ID}，回写序列化时会被
     * {@code EDIT_SCRIPT} 剥掉，不会污染正文。
     *
     * @param baseHref 章节在资源镜像里的目录 URI；为 {@code null} 时等价于两参重载
     */
    public static String editableDocument(String xhtml, Theme theme, String baseHref) {
        // 章节可能带着历史版本回写进去的编辑脚本（旧版序列化剥不掉 script）——
        // 加载时先剥掉，DOM 里就只剩下面刚注入的、带 id 的这一份；
        // 否则历史脚本会被再次序列化回书里，永远洗不掉
        String document = withBaseHref(
                injectStyle(stripInjectedScript(xhtml), editorStyleTag(theme)), baseHref);
        String editable = addContentEditable(document);
        int headClose = indexOfIgnoringCase(editable, "</head>");
        if (headClose < 0) {
            return editable;
        }
        return editable.substring(0, headClose) + EDIT_SCRIPT + "\n" + editable.substring(headClose);
    }

    /** 给 {@code <body>} 开标签补 {@code contenteditable="true"}。 */
    private static String addContentEditable(String doc) {
        int bodyStart = indexOfIgnoringCase(doc, "<body");
        if (bodyStart < 0) {
            return doc;
        }
        int tagEnd = doc.indexOf('>', bodyStart);
        if (tagEnd < 0) {
            return doc;
        }
        return doc.substring(0, tagEnd) + " contenteditable=\"true\"" + doc.substring(tagEnd);
    }

    /**
     * 编辑脚本的特征：{@code <script>} 块内容里出现我们的入口命名空间 {@code window.epubra}。
     *
     * <p>不用 id 定位——历史版本注入的脚本<b>没有 id</b>，只有内容特征能同时覆盖
     * 「旧的无 id 脚本」与「现行带 id 脚本」两种形态。作者自己的脚本（不含
     * {@code window.epubra}）不会被误伤。
     */
    private static final Pattern INJECTED_SCRIPT = Pattern.compile(
            "<script\\b[^>]*>(?:(?!</script>).)*?window\\.epubra(?:(?!</script>).)*?</script>\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 剥掉章节里混入的编辑脚本（三道防线之二）。
     *
     * <ol>
     *   <li>JS 序列化按 {@link #INJECTED_SCRIPT_ID} 剥掉刚注入的那份（第一道，主防线）；</li>
     *   <li>本方法在<b>加载时</b>清洗历史污染——旧版回写进正文的无 id 脚本在这里消失，
     *       下一次编辑保存后章节即自愈；</li>
     *   <li>Java 侧回写前再调一次本方法（第三道），任何序列化异常都到不了书里。</li>
     * </ol>
     *
     * @return 剥离后的文档；入参为 {@code null} / 空串时原样返回
     */
    public static String stripInjectedScript(String xhtml) {
        if (xhtml == null || xhtml.isEmpty()) {
            return xhtml;
        }
        return INJECTED_SCRIPT.matcher(xhtml).replaceAll("");
    }

    /**
     * 编辑脚本的「外壳」。脚本本体（约 780 行）外置在
     * {@code resources/org/chobit/epubra/app/editor/editor-script.js}，避免一个 Java 文件被
     * 几百行 JS 撑爆、也让前端语法能高亮。
     *
     * <p>本体整体包在 CDATA 里——文档按 XML 解析，脚本中的 {@code <} / {@code &}
     * 会直接让文档解析失败。
     *
     * <p>外壳里的 {@code %3$s} 与本体里的 {@code %s} / {@code %3$s} 是三个注入元素的 id
     * 占位符，由 {@link #INJECTED_STYLE_ID} / {@link #INJECTED_BASE_ID} /
     * {@link #INJECTED_SCRIPT_ID} 代入：id 常量改名时脚本自动跟随，不会在 JS 里留下
     * 已经对不上的字面量。
     *
     * <h2>⚠ 外壳常量必须声明在 {@link #EDIT_SCRIPT} 之前</h2>
     * <p>Java 静态字段按书写顺序初始化，{@link #EDIT_SCRIPT} 的初始化表达式会读这两个常量。
     */
    private static final String SCRIPT_OPEN =
            "<script id=\"%3$s\" type=\"text/javascript\">//<![CDATA[\n";

    private static final String SCRIPT_CLOSE = "\n//]]></script>";

    private static final String EDIT_SCRIPT = editorScript();

    private static String editorScript() {
        return (SCRIPT_OPEN + loadResource("editor-script.js") + SCRIPT_CLOSE)
                .formatted(INJECTED_STYLE_ID, INJECTED_BASE_ID, INJECTED_SCRIPT_ID);
    }

    private static String styleTag(Theme theme) {
        return "<style id=\"" + INJECTED_STYLE_ID + "\" type=\"text/css\">\n"
                + theme.previewStyleCss() + "\n</style>";
    }

    /**
     * 编辑器画布的「纸面」配色：可视化编辑器的正文画布<b>永远用浅色</b>，
     * 不随主题走（对齐 WPS / Word 的「页面永远是白纸」模型——编辑的是要发布的书，
     * 画布跟界面主题同色调反而干扰对成品的判断，深色 / 护眼主题下尤其明显）。
     *
     * <p>叠在 {@link Theme#previewStyleCss()} 之后：同权重同特异性，靠书写顺序取胜，
     * 只覆盖颜色（背景 / 文字 / 边框 / 链接 / 代码块），字体、字号、行距仍由主题层提供。
     * 整个 {@code <style>} 共用 {@link #INJECTED_STYLE_ID}，回写序列化时随注入样式一起剥掉。
     */
    private static final String EDITOR_PAPER_CSS = loadResource("editor-paper.css");

    private static String editorStyleTag(Theme theme) {
        return "<style id=\"" + INJECTED_STYLE_ID + "\" type=\"text/css\">\n"
                + theme.previewStyleCss() + EDITOR_PAPER_CSS + "\n</style>";
    }

    /**
     * 读类路径下的编辑器资源（UTF-8），并把行尾统一成 {@code \n}。
     *
     * <p>行尾必须归一：Windows 检出时资源文件是 CRLF，直接注入文档会让同一段脚本
     * 在不同机器上序列化出不同字节，而回写净化（{@code tidyHeadWhitespace} 等）是按
     * {@code \n} 判定空白间隙的。
     *
     * <p>资源缺失时<b>立刻抛</b>而不是返回空串：编辑脚本没了等于可视化编辑器全线失灵，
     * 静默降级只会让现象变成「点了没反应」，排查成本远高于启动即失败。
     */
    private static String loadResource(String name) {
        String path = "/org/chobit/epubra/app/editor/" + name;
        try (InputStream in = PreviewHtml.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺少编辑器资源：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("读取编辑器资源失败：" + path, e);
        }
    }

    private static int indexOfIgnoringCase(String text, String token) {
        return text.toLowerCase().indexOf(token.toLowerCase());
    }

    /**
     * 返回 {@code <head ...>} 开标签结束位置（即 {@code >} 之后的下标）；未找到返回 -1。
     *
     * <p>不能用通用的 {@link #endOfOpenTag(String, String)} 找 {@code <head}：它会把
     * {@code <header>} 也算进去。
     */
    private static int endOfHeadOpenTag(String text) {
        int from = 0;
        while (from < text.length()) {
            int found = indexOfIgnoringCase(text.substring(from), "<head");
            if (found < 0) {
                return -1;
            }
            int start = from + found;
            int after = start + 5;
            if (after < text.length()) {
                char next = text.charAt(after);
                if (next == '>' || Character.isWhitespace(next)) {
                    int end = text.indexOf('>', after);
                    return end < 0 ? -1 : end + 1;
                }
            }
            from = after;
        }
        return -1;
    }

    /** XML 属性值转义：镜像目录可能带 {@code &} 这类字符（用户目录名）。 */
    private static String escapeAttribute(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** 返回 {@code <html ...>} 这类开标签结束位置（即 {@code >} 之后的下标）；未找到返回 -1。 */
    private static int endOfOpenTag(String text, String openTagPrefix) {
        int start = indexOfIgnoringCase(text, openTagPrefix);
        if (start < 0) {
            return -1;
        }
        int end = text.indexOf('>', start);
        return end < 0 ? -1 : end + 1;
    }
}
