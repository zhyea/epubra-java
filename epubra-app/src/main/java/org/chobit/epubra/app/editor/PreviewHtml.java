package org.chobit.epubra.app.editor;

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
     * 编辑脚本。整体包在 CDATA 里——文档按 XML 解析，脚本中的 {@code <} / {@code &}
     * 会直接让文档解析失败。
     */
    private static final String EDIT_SCRIPT = """
            <script id="%3$s" type="text/javascript">//<![CDATA[
            (function () {
              var INJECTED_IDS = ['%s', '%s', '%3$s'];
              function stripInjected(root) {
                for (var i = 0; i < INJECTED_IDS.length; i++) {
                  var el = root.querySelector('#' + INJECTED_IDS[i]);
                  if (el && el.parentNode) { el.parentNode.removeChild(el); }
                }
              }
              // 每次加载都会往 head 注入 base / style / script 三个元素，其中 style 与
              // script 各带一个换行；stripInjected 只删元素、把相邻的空白文本节点留在
              // head 里，回写进章节后下一轮再注入再剥离，空行就跨周期线性累积（#59）。
              // 这里把 head 内的空白收敛成「每个间隙一个换行」：既阻断累积，又能自愈
              // 历史上已被撑开的 head。head 只放元数据、空白无语义，规范化不影响渲染；
              // body（含 pre/code 的缩进）一律不动，保留作者排版。
              function tidyHeadWhitespace(root) {
                var head = root.querySelector('head');
                if (!head) { return; }
                for (var c = head.firstChild; c; ) {
                  var next = c.nextSibling;
                  if (c.nodeType === 3 && !c.data.replace(/\\s/g, '')) {
                    var prev = c.previousSibling;
                    if (prev && prev.nodeType === 3 && !prev.data.replace(/\\s/g, '')) {
                      head.removeChild(c);
                    } else {
                      c.data = '\\n';
                    }
                  }
                  c = next;
                }
              }
              function serialize() {
                var clone = document.documentElement.cloneNode(true);
                stripInjected(clone);
                tidyHeadWhitespace(clone);
                rescueStrayNodes(clone);
                pruneEmptyInline(clone);
                pruneEmptyLists(clone);
                var body = clone.querySelector('body');
                if (body) { body.removeAttribute('contenteditable'); }
                return '<?xml version="1.0" encoding="UTF-8"?>\\n' +
                       new XMLSerializer().serializeToString(clone);
              }
              // 把 body 之外的元素节点（历史损坏残留：正文块挂在 html 层）收编回 body，
              // 挪回后回写的 XHTML 才合法，下次加载内容才能正常展示
              function rescueStrayNodes(root) {
                var body = root.querySelector('body');
                if (!body) { return; }
                var stray = [];
                for (var c = root.firstChild; c; c = c.nextSibling) {
                  // 这里用 nodeName 判定，不用 root.head：文档按 XML 解析时 Element 上
                  // 不保证有 head 属性（那是 HTMLDocument 的接口），取不到就是 undefined，
                  // 判定恒真 → 会把 head 当成 body 之外的残留节点搬进 body
                  if (c.nodeType === 1 && c.nodeName.toLowerCase() !== 'head' && c !== body) {
                    stray.push(c);
                  }
                }
                for (var i = 0; i < stray.length; i++) { body.appendChild(stray[i]); }
              }
              // 空行内标签是纯噪音（无锚文本的强调、无内容的强调壳），回写前剔除。
              // 循环到不动点，处理 <strong><em></em></strong> 这类嵌套空壳：
              // 内层删掉后外层变空，下一轮接着删。只清强调类语义标签，
              // <a>（有 href 属性语义）与 <span>（可能有 class）不碰。
              function pruneEmptyInline(root) {
                var tags = 'strong,em,u,del,code,b,i,s';
                var changed = true;
                while (changed) {
                  changed = false;
                  var els = root.querySelectorAll(tags);
                  for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    var hasMedia = el.querySelector('img,br,hr,video,audio,iframe,object,svg,math');
                    if (!hasMedia && !el.textContent.replace(/\\s/g, '')) {
                      el.parentNode.removeChild(el);
                      changed = true;
                    }
                  }
                }
              }
              // 空列表是纯噪音：<ul></ul> / <ol></ol>（零 li），以及 li 里空无一物、
              // 没有任何文字的列表（刚点列表还没写、或把列表项内容删空）。与 pruneEmptyInline
              // 同层——DOM 里允许短暂存在这种编辑中间态（点列表按钮先给个落脚点），
              // 但绝不写进书（#55 的块级延伸）。
              // 循环到不动点：内层空列表删掉后外层 li 可能也空了，下一轮接着删。
              // 注意「有意义的内容」判定里**不含 br**——contenteditable 会给空块塞 <br/>，
              // 那是占位不是内容，算进去会让净化在真实使用中失效。
              function pruneEmptyLists(root) {
                var changed = true;
                while (changed) {
                  changed = false;
                  var lists = root.querySelectorAll('ul,ol');
                  for (var i = 0; i < lists.length; i++) {
                    var el = lists[i];
                    var hasContent = el.querySelector('img,hr,video,audio,iframe,object,svg,math');
                    if (!hasContent && !el.textContent.replace(/\\s/g, '')) {
                      el.parentNode.removeChild(el);
                      changed = true;
                    }
                  }
                }
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

              // 光标所在的最外层块（body 的直接子元素）。
              // node 不在 body 内（body 被删空后光标落在 body/html 层）时必须返回 null——
              // 旧实现一路上溯会返回 document，后续 moveChildren/replaceChild 就把
              // 整个文档树搬进节点或在 null 父节点上抛错，标签被打到 body 外（#58）。
              function topBlock(node) {
                var n = node;
                if (!n) { return null; }
                if (n.nodeType !== 1) { n = n.parentNode; }
                if (!n || n === document.body) { return null; }
                while (n && n.parentNode && n.parentNode !== document.body) { n = n.parentNode; }
                return (n && n.parentNode === document.body) ? n : null;
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

              // 块级标签集合：这些孩子不能塞进 p/h2/h3 这类行内容器
              //（p>ul、p>p 都会让回写进书的 XHTML 结构损坏，再次加载后内容展示异常）
              function isBlockTag(t) {
                return t === 'p' || t === 'h1' || t === 'h2' || t === 'h3'
                        || t === 'ul' || t === 'ol' || t === 'blockquote'
                        || t === 'hr' || t === 'div' || t === 'pre' || t === 'table';
              }

              // 把容器的块级孩子摘出来按原序返回，剩下的行内容子再搬运
              function pullOutBlocks(container) {
                var out = [];
                var c = container.firstChild;
                while (c) {
                  var next = c.nextSibling;
                  if (isBlockTag(tagOf(c))) { out.push(c); container.removeChild(c); }
                  c = next;
                }
                return out;
              }

              // 块级节点数组按原序接到 anchor 后面，返回新的追加锚点
              function appendBlocksAfter(blocks, anchor) {
                for (var i = 0; i < blocks.length; i++) {
                  anchor.parentNode.insertBefore(blocks[i], anchor.nextSibling);
                  anchor = blocks[i];
                }
                return anchor;
              }

              // 把光标所在块换掉当前块；块在列表里则先脱离列表
              function formatBlock(tag) {
                var s = activeSelection();
                if (!s) { return false; }
                var range = s.getRangeAt(0);
                var block = topBlock(range.startContainer);
                if (!block) {
                  // body 空 / 光标不在块上：直接在 body 末尾建目标块（同 toggleList，
                  // 不返回 false——fallback 会把标签插到源码区去）
                  var blank = makeTag(tag);
                  document.body.appendChild(blank);
                  collapseInto(blank);
                  return true;
                }

                if (isList(block)) {
                  var item = childOfContaining(block, range.startContainer);
                  if (tagOf(item) !== 'li') {
                    // 整列表被选中（起点就是列表本身）：与 toggleList 的「退回段落」同语义，
                    // 每个 li 各转一段；标题/引用对整列表没有明确语义，按「有意不作为」处理。
                    // 两条路径都必须返回 true——返回 false 会让 Java 侧 fallback 往源码区
                    // 插骨架，切 tab 时用源码覆盖章节，可视化改动整个冲掉（#57/#60 同族事故）。
                    if (tag === 'p') { return !!listToBlocks(block); }
                    return true;
                  }
                  var lifted = makeTag(tag);
                  // li 里的块级孩子（Tab 缩进的子列表等）不能进新块——摘出来跟在后面
                  var blocks = pullOutBlocks(item);
                  moveChildren(item, lifted);
                  block.parentNode.insertBefore(lifted, block.nextSibling);
                  appendBlocksAfter(blocks, lifted);
                  block.removeChild(item);
                  if (!block.querySelector('li')) { block.parentNode.removeChild(block); }
                  collapseInto(lifted);
                  return true;
                }

                if (tagOf(block) === tag) { collapseInto(block); return true; }
                var fresh = makeTag(tag);
                var movedBlocks = [];
                if (tag === 'p' || tag === 'h1' || tag === 'h2' || tag === 'h3') {
                  // 引用块转段落等场景：块级孩子绝不能塞进行内容器（p>p 同样非法）
                  movedBlocks = pullOutBlocks(block);
                }
                moveChildren(block, fresh);
                block.parentNode.replaceChild(fresh, block);
                appendBlocksAfter(movedBlocks, fresh);
                collapseInto(fresh);
                return true;
              }

              // 整个列表退回段落：每个顶层 li 各转成一段，li 内的块级孩子
              //（子列表等）原样跟在对应段之后——多次点「列表」往返时结构保持合法
              function listToBlocks(block) {
                var frag = document.createDocumentFragment();
                var item = block.firstElementChild;
                while (item) {
                  var next = item.nextElementSibling;
                  if (tagOf(item) === 'li') {
                    var p = makeTag('p');
                    var blocks = pullOutBlocks(item);
                    moveChildren(item, p);
                    frag.appendChild(p);
                    appendBlocksAfter(blocks, p);
                  } else {
                    frag.appendChild(item);
                  }
                  item = next;
                }
                // 先取 lastChild 再替换：appendChild(frag) 会把子节点移交进 DOM，
                // 替换之后 frag 已空，frag.lastChild 恒为 null（踩实过：返回 false
                // 会让 Java 侧 fallback 往源码区插列表骨架）
                var last = frag.lastChild;
                block.parentNode.replaceChild(frag, block);
                return last;
              }

              // 段落 / 标题 / 列表 三者互转；已经是列表了再点一次退回段落；
              // ul 与 ol 互相点则直接换列表类型，不必先退回段落。
              function toggleList(kind) {
                var s = activeSelection();
                if (!s || !s.rangeCount) { return false; }
                var range = s.getRangeAt(0);
                var block = topBlock(range.startContainer);
                if (!block) {
                  // body 空 / 光标不在块上：直接在 body 末尾建列表骨架，
                  // 不返回 false（会触发源码 fallback，骨架会被插到源码区去）
                  var blankList = makeTag(kind);
                  var blankItem = makeTag('li');
                  blankList.appendChild(blankItem);
                  document.body.appendChild(blankList);
                  collapseInto(blankItem);
                  return true;
                }
                if (isList(block)) {
                  if (tagOf(block) === kind) {
                    // 退回段落。整列表被选中时（起点就是列表本身，如整段选中后再点）
                    // childOfContaining 找不到 li——此时把每个 li 各转成一段；
                    // 若这里返回 false，Java 侧会 fallback 去源码区插列表骨架，
                    // 切 tab 时把可视化改动整个冲掉（多次点列表后内容错乱的根源之一）
                    var item = childOfContaining(block, range.startContainer);
                    if (tagOf(item) !== 'li') {
                      return !!listToBlocks(block);
                    }
                    return formatBlock('p');
                  }
                  var converted = makeTag(kind);
                  moveChildren(block, converted);
                  block.parentNode.replaceChild(converted, block);
                  return true;
                }

                var list = makeTag(kind);
                var li = makeTag('li');
                moveChildren(block, li);
                list.appendChild(li);
                block.parentNode.replaceChild(list, block);
                collapseInto(li);
                return true;
              }

              // ---- 列表层级：Tab 缩进 / Shift+Tab 降级 ------------------------
              function closestListItem(node) {
                var n = (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
                while (n && n !== document.body) {
                  if (tagOf(n) === 'li') { return n; }
                  n = n.parentNode;
                }
                return null;
              }

              // Tab：把当前 li 挪进前一项里的同类型子列表（第一项没有前项，缩不了）
              function indentListItem(li) {
                var list = li.parentNode;
                if (!list || (tagOf(list) !== 'ul' && tagOf(list) !== 'ol')) { return false; }
                var prev = li.previousSibling;
                while (prev && tagOf(prev) !== 'li') { prev = prev.previousSibling; }
                if (!prev) { return false; }
                var sub = makeTag(tagOf(list));
                prev.appendChild(sub);
                sub.appendChild(li);
                collapseInto(li);
                return true;
              }

              // Shift+Tab：把当前 li（连同它后面的兄弟项）提出来放到宿主 li 之后；
              // 已经是顶层列表的项没有层级可降，维持原状。
              function outdentListItem(li) {
                var list = li.parentNode;
                if (!list || (tagOf(list) !== 'ul' && tagOf(list) !== 'ol')) { return false; }
                var hostLi = list.parentNode;
                if (tagOf(hostLi) !== 'li') { return false; }
                var moved = document.createDocumentFragment();
                var n = li;
                while (n) { var next = n.nextSibling; moved.appendChild(n); n = next; }
                hostLi.parentNode.insertBefore(moved, hostLi.nextSibling);
                if (!list.querySelector('li')) { list.parentNode.removeChild(list); }
                collapseInto(li);
                return true;
              }

              // 行内格式切换：光标/选区已在包裹层内则拆掉（含嵌套同名层），否则包上。
              // 拆掉后选区落在原被包裹内容上——用户可以直接再点一次重新包上。
              // 旧实现只会往上包：再点一次变成嵌套 <strong><strong>，永远取消不掉。
              function findWrap(node, tag) {
                var n = (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
                while (n && n !== document.body) {
                  if (tagOf(n) === tag) { return n; }
                  n = n.parentNode;
                }
                return null;
              }

              // 选区是否与 tag 元素相交——选区内只要含有该格式的文字就算命中
              function selectionTouches(range, tag) {
                var root = (range.commonAncestorContainer.nodeType === 1)
                        ? range.commonAncestorContainer
                        : range.commonAncestorContainer.parentNode;
                // 选区就落在某个 tag 元素内部（包裹应用后 selectContents 的形态：
                // commonAncestor 就是包裹本身，getElementsByTagName 在其内部找不到自身）
                for (var p = root; p && p !== document.body; p = p.parentNode) {
                  if (tagOf(p) === tag) { return true; }
                }
                // 或选区横跨了包裹的边界，在共同祖先的子树里找相交的 tag 元素
                var els = root.getElementsByTagName(tag);
                for (var i = 0; i < els.length; i++) {
                  if (range.intersectsNode(els[i])) { return true; }
                }
                return false;
              }

              function unwrapEl(el) {
                var parent = el.parentNode;
                if (!parent) { return null; }
                var first = el.firstChild;
                var last = el.lastChild;
                while (el.firstChild) { parent.insertBefore(el.firstChild, el); }
                var range = null;
                if (first) {
                  range = document.createRange();
                  range.setStartBefore(first);
                  range.setEndAfter(last);
                }
                parent.removeChild(el);
                return range;
              }

              function toggleInline(tag) {
                var s = activeSelection();
                if (!s || !s.rangeCount) { return false; }
                var range = s.getRangeAt(0);
                if (!range.collapsed) {
                  // 拖选：选区内含有该格式包裹就全部移除（与点亮语义严格对称——
                  // 亮 ⇔ 选区相交 ⇔ 点击移除），一个都没有才包裹新标签。
                  // 含锚点在包裹内的情形：锚点链上的包裹也在相交集合里，一并拆掉。
                  return unwrapCovered(range, tag, s) || wrapInline(tag);
                }
                // 光标态：落在包裹内 = 取消该包裹（#54）；否则不做格式化
                //（避免空标签占位）。返回 true 的原因：false 会让 Java 侧
                // fallback 去往源码区插片段，把空标签换个地方再犯。
                var existing = findWrap(range.startContainer, tag);
                if (!existing) { return true; }
                var last = null;
                while (existing) {
                  last = unwrapEl(existing);
                  existing = last ? findWrap(last.startContainer, tag) : null;
                }
                if (last) { s.removeAllRanges(); s.addRange(last); }
                return true;
              }

              // 拆掉选区命中（祖先链上或子树内相交）的所有 tag 包裹，
              // 文档序逐个解开，保持选区落在最后解开处
              function unwrapCovered(range, tag, s) {
                var root = (range.commonAncestorContainer.nodeType === 1)
                        ? range.commonAncestorContainer
                        : range.commonAncestorContainer.parentNode;
                var targets = [];
                // 包裹应用后选区常锚定在包裹本身（commonAncestor 就是它），
                // 子树查找看不到它——祖先链要先并进来
                for (var p = root; p && p !== document.body; p = p.parentNode) {
                  if (tagOf(p) === tag) { targets.push(p); }
                }
                var all = root.getElementsByTagName(tag);
                for (var i = 0; i < all.length; i++) {
                  if (range.intersectsNode(all[i])) { targets.push(all[i]); }
                }
                var last = null;
                for (var j = 0; j < targets.length; j++) { last = unwrapEl(targets[j]); }
                if (last) { s.removeAllRanges(); s.addRange(last); }
                return targets.length > 0;
              }

              // 行内包裹：只在有实际选区时包起来（选区跨元素时退化为「抽出→包裹→放回」）。
              // 无选区的空标签占位（<em></em>）已废除——空行内标签对 XHTML 是纯噪音，
              // toggleInline 在光标态直接 no-op。
              function wrapInline(tag) {
                var s = activeSelection();
                if (!s) { return false; }
                var range = s.getRangeAt(0);
                var el = makeTag(tag);
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
                else if (kind === 'list') { ok = toggleList('ul'); }
                else if (kind === 'ol') { ok = toggleList('ol'); }
                else if (kind === 'rule') { ok = insertRule(); }
                else if (kind === 'bold') { ok = toggleInline('strong'); }
                else if (kind === 'italic') { ok = toggleInline('em'); }
                else if (kind === 'underline') { ok = toggleInline('u'); }
                else if (kind === 'strike') { ok = toggleInline('del'); }
                else if (kind === 'code') { ok = toggleInline('code'); }
                else if (kind === 'link') { ok = wrapLink(value); }
                else if (kind === 'unlink') { ok = unwrapLink(); }
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

              // 链接：选区包成 <a href>；空选区插一对空 <a> 并把光标落在中间。
              // 光标已在链接内时（epubraQueryLink 返回非空），wrapLink 不再嵌套，
              // 直接改写现有链接的 href——「编辑链接」语义。
              function wrapLink(href) {
                var safe = safeUrl(href, false);
                if (!safe) { return false; }
                var s = activeSelection();
                if (!s || !s.rangeCount) { return false; }
                var range = s.getRangeAt(0);
                var existing = findWrap(range.startContainer, 'a');
                if (existing) {
                  existing.setAttribute('href', safe);
                  return true;
                }
                var el = makeTag('a');
                el.setAttribute('href', safe);
                if (range.collapsed) {
                  // 无选区不插空 <a href="…"></a>（链接没锚文本就没意义）；返回 true
                  // 同 toggleInline：避免 Java 侧 fallback 去源码区插片段
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

              // 取消链接：拆掉光标/选区所在的 <a>，保留里面的文字
              function unwrapLink() {
                var s = activeSelection();
                if (!s || !s.rangeCount) { return false; }
                var wrapped = findWrap(s.getRangeAt(0).startContainer, 'a');
                if (!wrapped) { return false; }
                var last = null;
                while (wrapped) {
                  last = unwrapEl(wrapped);
                  wrapped = last ? findWrap(last.startContainer, 'a') : null;
                }
                if (last) { s.removeAllRanges(); s.addRange(last); }
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
                if (range.collapsed) {
                  var n = (range.startContainer.nodeType === 1)
                          ? range.startContainer : range.startContainer.parentNode;
                  while (n && n !== document.body) {
                    var name = INLINE_FMT[tagOf(n)];
                    if (name && out.indexOf(name) < 0) { out.push(name); }
                    n = n.parentNode;
                  }
                } else {
                  // 拖选点亮语义：选区内只要含有该格式的文字（相交）就点亮对应按钮，
                  // 点击由 toggleInline 移除选区内的该格式包裹——亮 ⇔ 可移除，严格对称。
                  for (var t in INLINE_FMT) {
                    if (selectionTouches(range, t) && out.indexOf(INLINE_FMT[t]) < 0) {
                      out.push(INLINE_FMT[t]);
                    }
                  }
                }
                var block = closestBlock(range.startContainer);
                var bt = tagOf(block);
                if (bt === 'li') {
                  // 无序列表报 'list'（点「列表」按钮退回段落），有序列表报 'ol'
                  var ln = block.parentNode;
                  out.push(ln && tagOf(ln) === 'ol' ? 'ol' : 'list');
                }
                else if (bt === 'h1' || bt === 'h2' || bt === 'h3') { out.push('heading'); }
                else if (bt === 'blockquote') { out.push('quote'); }
                else if (bt === 'p') { out.push('paragraph'); }
                return out.join(' ');
              };

              // 光标所在链接的 href；不在链接里返回空串（供「插入链接」弹窗回填地址）
              window.epubraQueryLink = function () {
                var s = activeSelection();
                if (!s || !s.rangeCount) { return ''; }
                var found = findWrap(s.getRangeAt(0).startContainer, 'a');
                return found ? (found.getAttribute('href') || '') : '';
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
                // 列表里的 Tab / Shift+Tab = 多层级缩进 / 降级；不在列表里交给默认行为
                if ((e.key || '') === 'Tab' && !(e.ctrlKey || e.metaKey)) {
                  var s = activeSelection();
                  var li = (s && s.rangeCount) ? closestListItem(s.getRangeAt(0).startContainer) : null;
                  if (li) {
                    var done = e.shiftKey ? outdentListItem(li) : indentListItem(li);
                    e.preventDefault();
                    e.stopPropagation();
                    if (done) { push(); }
                  }
                  return;
                }
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
            //]]></script>""".formatted(INJECTED_STYLE_ID, INJECTED_BASE_ID, INJECTED_SCRIPT_ID);

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
    private static final String EDITOR_PAPER_CSS = """
                html, body { background: #faf9f5 !important; color: #2b2b28 !important; }
                p, li, h1, h2, h3, h4, h5, h6, div, section, article, blockquote, td, th, span, figcaption { color: #2b2b28 !important; border-color: #dedbd2 !important; }
                a { color: #1a5fb4 !important; }
                hr, table, th, td, pre, img { border-color: #dedbd2 !important; }
                pre, code { background: #f1efe8 !important; color: #2b2b28 !important; }
                ::selection { background: #cfe0f5 !important; color: #2b2b28 !important; }
            """;

    private static String editorStyleTag(Theme theme) {
        return "<style id=\"" + INJECTED_STYLE_ID + "\" type=\"text/css\">\n"
                + theme.previewStyleCss() + EDITOR_PAPER_CSS + "\n</style>";
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
