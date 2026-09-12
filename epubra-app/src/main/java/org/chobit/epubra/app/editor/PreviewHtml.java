package org.chobit.epubra.app.editor;

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
        if (xhtml == null || xhtml.isBlank()) {
            return emptyDocument(theme);
        }
        int headClose = indexOfIgnoringCase(xhtml, "</head>");
        if (headClose >= 0) {
            return xhtml.substring(0, headClose) + styleTag(theme) + "\n" + xhtml.substring(headClose);
        }
        int bodyStart = indexOfIgnoringCase(xhtml, "<body");
        if (bodyStart >= 0) {
            return xhtml.substring(0, bodyStart) + "<head>" + styleTag(theme) + "</head>"
                    + xhtml.substring(bodyStart);
        }
        int htmlTagEnd = endOfOpenTag(xhtml, "<html");
        if (htmlTagEnd >= 0) {
            return xhtml.substring(0, htmlTagEnd) + "<head>" + styleTag(theme) + "</head>"
                    + xhtml.substring(htmlTagEnd);
        }
        return "<html xmlns=\"" + XHTML_NS + "\"><head>" + styleTag(theme) + "</head><body>"
                + xhtml + "</body></html>";
    }

    /** 无章节时的空预览文档；背景跟随主题，避免出现一张刺眼的空白页。 */
    public static String emptyDocument(Theme theme) {
        return "<html xmlns=\"" + XHTML_NS + "\"><head>" + styleTag(theme) + "</head><body></body></html>";
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
     * <p>脚本通过 {@code window.epubraBridge.onEdited(xhtml)} 把结果推回 Java；回写前会先
     * 克隆一份 DOM 并剥掉注入的样式与 {@code contenteditable} 属性，保证推回去的是
     * <b>干净的正文</b>而不是带预览配色的副本。
     */
    public static String editableDocument(String xhtml, Theme theme) {
        String base = withTheme(xhtml, theme);
        String editable = addContentEditable(base);
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
     * 编辑脚本。整体包在 CDATA 里——文档按 XML 解析，脚本中的 {@code <} / {@code &}
     * 会直接让文档解析失败。
     */
    private static final String EDIT_SCRIPT = """
            <script type="text/javascript">//<![CDATA[
            (function () {
              var INJECTED_ID = '%s';
              function serialize() {
                var clone = document.documentElement.cloneNode(true);
                var injected = clone.querySelector('#' + INJECTED_ID);
                if (injected && injected.parentNode) { injected.parentNode.removeChild(injected); }
                var body = clone.querySelector('body');
                if (body) { body.removeAttribute('contenteditable'); }
                return '<?xml version="1.0" encoding="UTF-8"?>\\n' +
                       new XMLSerializer().serializeToString(clone);
              }
              function push() {
                if (window.epubraBridge) { window.epubraBridge.onEdited(serialize()); }
              }
              var timer = null;
              // 与源码编辑器的 600ms 撤销合并保持一致，避免每敲一键就回写一次
              document.addEventListener('input', function () {
                if (timer) { clearTimeout(timer); }
                timer = setTimeout(push, 600);
              });
              document.addEventListener('blur', function () {
                if (timer) { clearTimeout(timer); timer = null; }
                push();
              }, true);
              window.epubraSerialize = serialize;

              // ---- 工具条：对可视化编辑器做真正的富文本操作 ----------------
              //
              // 全部走 Range/DOM 手工操作，不用 document.execCommand：后者产出的标签
              // 随引擎而异（<b> / <span style> / <font>），而这里必须产出确定性的
              // XHTML（<strong> / <em>），否则回写正文会带上引擎私货。
              var NS = 'http://www.w3.org/1999/xhtml';

              function makeTag(name) { return document.createElementNS(NS, name); }
              function tagOf(el) { return (el && el.nodeType === 1) ? el.tagName.toLowerCase() : ''; }
              function isList(el) { var t = tagOf(el); return t === 'ul' || t === 'ol'; }

              function activeSelection() {
                var s = window.getSelection();
                return (s && s.rangeCount) ? s : null;
              }

              function selectContents(el) {
                var s = activeSelection();
                if (!s) { return; }
                var r = document.createRange();
                r.selectNodeContents(el);
                s.removeAllRanges();
                s.addRange(r);
              }

              function collapseInto(el) {
                var s = activeSelection();
                if (!s) { return; }
                var r = document.createRange();
                r.selectNodeContents(el);
                r.collapse(true);
                s.removeAllRanges();
                s.addRange(r);
              }

              // 光标所在的最外层块（body 的直接子元素）
              function topBlock(node) {
                var n = node;
                if (!n) { return null; }
                if (n.nodeType !== 1) { n = n.parentNode; }
                while (n && n.parentNode && n.parentNode !== document.body) { n = n.parentNode; }
                return (n && n !== document.body) ? n : null;
              }

              // block 里直接包住 node 的那一层（例如 ul 里的某个 li）
              function childOfContaining(block, node) {
                var n = node;
                while (n && n.parentNode && n.parentNode !== block) { n = n.parentNode; }
                return (n && n.parentNode === block) ? n : null;
              }

              function moveChildren(from, to) {
                while (from.firstChild) { to.appendChild(from.firstChild); }
              }

              // 把 tag 对应的块级元素换掉当前块；块在列表里则先脱离列表
              function formatBlock(tag) {
                var s = activeSelection();
                if (!s) { return false; }
                var range = s.getRangeAt(0);
                var block = topBlock(range.startContainer);
                if (!block) { return false; }

                if (isList(block)) {
                  var item = childOfContaining(block, range.startContainer);
                  if (tagOf(item) !== 'li') { return false; }
                  var lifted = makeTag(tag);
                  moveChildren(item, lifted);
                  block.parentNode.insertBefore(lifted, block.nextSibling);
                  block.removeChild(item);
                  if (!block.querySelector('li')) { block.parentNode.removeChild(block); }
                  collapseInto(lifted);
                  return true;
                }

                if (tagOf(block) === tag) { collapseInto(block); return true; }
                var fresh = makeTag(tag);
                moveChildren(block, fresh);
                block.parentNode.replaceChild(fresh, block);
                collapseInto(fresh);
                return true;
              }

              // 段落 / 标题 / 列表 三者互转；已经是列表了再点一次退回段落
              function toggleList() {
                var s = activeSelection();
                if (!s) { return false; }
                var block = topBlock(s.getRangeAt(0).startContainer);
                if (!block) { return false; }
                if (isList(block)) { return formatBlock('p'); }

                var list = makeTag('ul');
                var item = makeTag('li');
                moveChildren(block, item);
                list.appendChild(item);
                block.parentNode.replaceChild(list, block);
                collapseInto(item);
                return true;
              }

              // 行内包裹：有选区就包起来（选区跨元素时退化为「抽出→包裹→放回」），
              // 无选区就插一对空标签并把光标落在中间
              function wrapInline(tag) {
                var s = activeSelection();
                if (!s) { return false; }
                var range = s.getRangeAt(0);
                var el = makeTag(tag);
                if (range.collapsed) {
                  range.insertNode(el);
                  collapseInto(el);
                  return true;
                }
                try {
                  range.surroundContents(el);
                } catch (err) {
                  el.appendChild(range.extractContents());
                  range.insertNode(el);
                }
                selectContents(el);
                return true;
              }

              window.epubraFormat = function (kind) {
                var ok = false;
                if (kind === 'paragraph') { ok = formatBlock('p'); }
                else if (kind === 'heading') { ok = formatBlock('h2'); }
                else if (kind === 'bold') { ok = wrapInline('strong'); }
                else if (kind === 'italic') { ok = wrapInline('em'); }
                else if (kind === 'list') { ok = toggleList(); }
                if (ok) { push(); }
                return ok;
              };

              // 把一段 XHTML 片段插到光标处（图片等），成功返回 true
              window.epubraInsertHtml = function (html) {
                var s = activeSelection();
                if (!s || !html) { return false; }
                try {
                  var range = s.getRangeAt(0);
                  var frag = range.createContextualFragment(html);
                  var last = frag.lastChild;
                  range.deleteContents();
                  range.insertNode(frag);
                  if (last) {
                    var after = document.createRange();
                    after.setStartAfter(last);
                    after.collapse(true);
                    s.removeAllRanges();
                    s.addRange(after);
                  }
                  push();
                  return true;
                } catch (err) {
                  return false;
                }
              };
            })();
            //]]></script>""".formatted(INJECTED_STYLE_ID);

    private static String styleTag(Theme theme) {
        return "<style id=\"" + INJECTED_STYLE_ID + "\" type=\"text/css\">\n"
                + theme.previewStyleCss() + "\n</style>";
    }

    private static int indexOfIgnoringCase(String text, String token) {
        return text.toLowerCase().indexOf(token.toLowerCase());
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
