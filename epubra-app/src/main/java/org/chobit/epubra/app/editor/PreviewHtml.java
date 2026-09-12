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

    /**
     * 注入的 {@code <base>} 的元素 id。
     *
     * <p>可视化编辑器回写正文时同样要把它剥掉——镜像目录是运行期临时产物，
     * 一旦写进书里就成了指向 {@code ~/.Epubra/} 的绝对路径，换台机器整章图片全断。
     */
    static final String INJECTED_BASE_ID = "epubra-preview-base";

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
     *   <li>{@code window.epubraFormat(kind[, value])} —— 段落/标题/引用/列表/分隔线/
     *       加粗/斜体/下划线/删除线/行内代码/链接</li>
     *   <li>{@code window.epubraInsertHtml(html)} —— 片段插到光标处（图片等）</li>
     *   <li>{@code window.epubraQuery()} —— 当前生效格式名（空格分隔）</li>
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
        String document = withBaseHref(withTheme(xhtml, theme), baseHref);
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
     * 编辑脚本。整体包在 CDATA 里——文档按 XML 解析，脚本中的 {@code <} / {@code &}
     * 会直接让文档解析失败。
     */
    private static final String EDIT_SCRIPT = """
            <script type="text/javascript">//<![CDATA[
            (function () {
              var INJECTED_IDS = ['%s', '%s'];
              function stripInjected(root) {
                for (var i = 0; i < INJECTED_IDS.length; i++) {
                  var el = root.querySelector('#' + INJECTED_IDS[i]);
                  if (el && el.parentNode) { el.parentNode.removeChild(el); }
                }
              }
              function serialize() {
                var clone = document.documentElement.cloneNode(true);
                stripInjected(clone);
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

              // ---- 命令表（工具条 / 快捷键共用） ------------------------------
              window.epubraFormat = function (kind, value) {
                var ok = false;
                if (kind === 'paragraph') { ok = formatBlock('p'); }
                else if (kind === 'heading') { ok = formatBlock('h2'); }
                else if (kind === 'quote') { ok = toggleQuote(); }
                else if (kind === 'list') { ok = toggleList(); }
                else if (kind === 'rule') { ok = insertRule(); }
                else if (kind === 'bold') { ok = wrapInline('strong'); }
                else if (kind === 'italic') { ok = wrapInline('em'); }
                else if (kind === 'underline') { ok = wrapInline('u'); }
                else if (kind === 'strike') { ok = wrapInline('del'); }
                else if (kind === 'code') { ok = wrapInline('code'); }
                else if (kind === 'link') { ok = wrapLink(value); }
                if (ok) { push(); }
                return ok;
              };

              // 引用：与段落互转（再点一次退回段落）
              function toggleQuote() {
                var s = activeSelection();
                if (!s) { return false; }
                var block = topBlock(s.getRangeAt(0).startContainer);
                if (!block) { return false; }
                return formatBlock(tagOf(block) === 'blockquote' ? 'p' : 'blockquote');
              }

              // 分隔线：插在当前块之后，并在它下面补一个空段落让光标有落点
              function insertRule() {
                var s = activeSelection();
                if (!s) { return false; }
                var block = topBlock(s.getRangeAt(0).startContainer);
                if (!block) { return false; }
                var p = makeTag('p');
                block.parentNode.insertBefore(makeTag('hr'), block.nextSibling);
                block.parentNode.insertBefore(p, block.nextSibling.nextSibling);
                collapseInto(p);
                return true;
              }

              // 链接：选区包成 <a href>；空选区插一对空 <a> 并把光标落在中间
              function wrapLink(href) {
                var safe = safeUrl(href, false);
                if (!safe) { return false; }
                var s = activeSelection();
                if (!s) { return false; }
                var range = s.getRangeAt(0);
                var el = makeTag('a');
                el.setAttribute('href', safe);
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

              // ---- 光标状态查询：驱动工具条的「按下」态 -----------------------
              var INLINE_FMT = { strong: 'bold', em: 'italic', u: 'underline',
                                 del: 'strike', code: 'code', a: 'link' };
              var BLOCK_NAMES = { p: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1,
                                  li: 1, blockquote: 1, pre: 1, div: 1 };

              function closestBlock(node) {
                var n = (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
                while (n && n !== document.body) {
                  if (BLOCK_NAMES[tagOf(n)]) { return n; }
                  n = n.parentNode;
                }
                return null;
              }

              window.epubraQuery = function () {
                var s = activeSelection();
                if (!s) { return ''; }
                var range = s.getRangeAt(0);
                var out = [];
                var n = (range.startContainer.nodeType === 1)
                        ? range.startContainer : range.startContainer.parentNode;
                while (n && n !== document.body) {
                  var name = INLINE_FMT[tagOf(n)];
                  if (name && out.indexOf(name) < 0) { out.push(name); }
                  n = n.parentNode;
                }
                var block = closestBlock(range.startContainer);
                var bt = tagOf(block);
                if (bt === 'li') { out.push('list'); }
                else if (bt === 'h1' || bt === 'h2' || bt === 'h3') { out.push('heading'); }
                else if (bt === 'blockquote') { out.push('quote'); }
                else if (bt === 'p') { out.push('paragraph'); }
                return out.join(' ');
              };

              function notifySelection() {
                if (!window.epubraBridge || !window.epubraBridge.onSelectionChanged) { return; }
                window.epubraBridge.onSelectionChanged(window.epubraQuery());
              }
              document.addEventListener('selectionchange', notifySelection);
              document.addEventListener('keyup', notifySelection);
              document.addEventListener('mouseup', notifySelection);

              // ---- 快捷键 ----------------------------------------------------
              // 用捕获阶段：编辑器的默认处理在目标元素上，冒泡阶段拦不住。
              // Ctrl+Z / Ctrl+Y 交给 Java 的应用级快照撤销，避免两套撤销栈打架。
              document.addEventListener('keydown', function (e) {
                if (!(e.ctrlKey || e.metaKey)) { return; }
                var k = (e.key || '').toLowerCase();
                var handled = true;
                if (k === 'b') { window.epubraFormat('bold'); }
                else if (k === 'i') { window.epubraFormat('italic'); }
                else if (k === 'u') { window.epubraFormat('underline'); }
                else if (k === 'z' && !e.shiftKey) { bridgeCall('onUndo'); }
                else if (k === 'y' || (k === 'z' && e.shiftKey)) { bridgeCall('onRedo'); }
                else { handled = false; }
                if (handled) { e.preventDefault(); e.stopPropagation(); }
              }, true);

              function bridgeCall(name) {
                if (window.epubraBridge && window.epubraBridge[name]) { window.epubraBridge[name](); }
              }

              // ---- 粘贴净化 --------------------------------------------------
              // 外部富文本带着任意标签 / 内联样式进来，直接落进正文会让产出的
              // EPUB 带上非法结构。这里只放行白名单标签，其余拆掉标签保留文字。
              var ALLOWED = { p: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1,
                              ul: 1, ol: 1, li: 1, blockquote: 1, pre: 1, hr: 1, br: 1,
                              strong: 1, em: 1, u: 1, del: 1, code: 1, a: 1, img: 1 };
              var ALIAS = { b: 'strong', i: 'em', strike: 'del', s: 'del', ins: 'u' };
              var KEEP_ATTRS = { a: ['href'], img: ['src', 'alt'] };
              var DROPPED = { script: 1, style: 1, head: 1, meta: 1, link: 1, title: 1,
                              iframe: 1, object: 1, embed: 1, form: 1, input: 1, svg: 1 };

              // 挡掉 javascript: / vbscript:；data: 只允许出现在 img 上
              function safeUrl(value, allowData) {
                if (!value) { return null; }
                var probe = value.replace(/[\\u0000-\\u0020]/g, '').toLowerCase();
                if (probe.indexOf('javascript:') === 0) { return null; }
                if (probe.indexOf('vbscript:') === 0) { return null; }
                if (!allowData && probe.indexOf('data:') === 0) { return null; }
                return value;
              }

              function copySanitized(from, to) {
                var kids = from.childNodes;
                for (var i = 0; i < kids.length; i++) {
                  var n = kids[i];
                  if (n.nodeType === 3) {
                    to.appendChild(document.createTextNode(n.nodeValue));
                    continue;
                  }
                  if (n.nodeType !== 1) { continue; }
                  var name = n.tagName.toLowerCase();
                  if (DROPPED[name]) { continue; }
                  if (!ALLOWED[name] && !ALIAS[name]) { copySanitized(n, to); continue; }
                  var mapped = ALIAS[name] ? ALIAS[name] : name;
                  var el = makeTag(mapped);
                  var keep = KEEP_ATTRS[mapped] || [];
                  for (var j = 0; j < keep.length; j++) {
                    var raw = n.getAttribute(keep[j]);
                    var ok = safeUrl(raw, mapped === 'img');
                    if (ok) { el.setAttribute(keep[j], ok); }
                  }
                  copySanitized(n, el);
                  to.appendChild(el);
                }
              }

              function sanitize(html) {
                var out = document.createDocumentFragment();
                var parsed = new DOMParser().parseFromString(html, 'text/html');
                if (!parsed || !parsed.body) { return out; }
                copySanitized(parsed.body, out);
                return out;
              }

              // 供测试与 Java 侧复用：返回净化结果的序列化文本
              window.epubraSanitize = function (html) {
                var box = makeTag('div');
                box.appendChild(sanitize(html));
                return new XMLSerializer().serializeToString(box);
              };

              document.addEventListener('paste', function (e) {
                var dt = e.clipboardData;
                if (!dt) { return; }
                e.preventDefault();
                e.stopPropagation();
                var html = dt.getData('text/html');
                var text = dt.getData('text/plain');
                if (html) { insertFragment(sanitize(html)); }
                else { insertPlainText(text); }
              }, true);

              function insertPlainText(text) {
                if (!text) { return; }
                var lines = text.split('\\n');
                if (lines.length === 1) {
                  var only = document.createDocumentFragment();
                  only.appendChild(document.createTextNode(text));
                  insertFragment(only);
                  return;
                }
                var frag = document.createDocumentFragment();
                for (var i = 0; i < lines.length; i++) {
                  var line = lines[i].replace('\\r', '');
                  if (!line.length) { continue; }
                  var p = makeTag('p');
                  p.appendChild(document.createTextNode(line));
                  frag.appendChild(p);
                }
                insertFragment(frag);
              }

              // 把一段 XHTML 片段插到光标处（图片等），成功返回 true
              window.epubraInsertHtml = function (html) {
                if (!html) { return false; }
                try {
                  var range = activeSelection().getRangeAt(0);
                  return insertFragment(range.createContextualFragment(html));
                } catch (err) {
                  return false;
                }
              };

              // 公共插入：片段落在光标处，光标移到片段之后
              function insertFragment(frag) {
                var s = activeSelection();
                if (!s || !frag) { return false; }
                var range = s.getRangeAt(0);
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
                notifySelection();
                return true;
              }
            })();
            //]]></script>""".formatted(INJECTED_STYLE_ID, INJECTED_BASE_ID);

    private static String styleTag(Theme theme) {
        return "<style id=\"" + INJECTED_STYLE_ID + "\" type=\"text/css\">\n"
                + theme.previewStyleCss() + "\n</style>";
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
