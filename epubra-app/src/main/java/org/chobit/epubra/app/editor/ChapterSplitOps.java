package org.chobit.epubra.app.editor;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.TOCReference;
import org.chobit.epubra.lib.domain.TocEditor;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 章节拆分的纯逻辑：把一章 XHTML 按「前后缀标记 / 正则 / 每段字数」切成多段，
 * 以及把切好的段应用到 {@link Book}（新章插入原章节同级之后，阅读顺序由内核重排）。
 *
 * <p><b>拆分以正文顶层块为单位</b>（body 的直接子元素，通常是 {@code <p>}）：标记与正则
 * 都在「块的纯文本」上匹配，命中块即新章起点。绝不在块中间下刀——那样每段都不再是
 * 合法 XHTML；按字数拆也一样，攒满字数后在下一个块边界收段。
 *
 * <p>每段产出一份完整 XHTML：沿用原章节的 {@code <head>}（样式表、字体引用不丢），
 * 只替换 {@code <title>}；html 开标签也取自原文档（保留 lang 等属性）。
 *
 * <p>与本包 {@link TextSearch} 同族：与界面无关、无 JavaFX 依赖，可纯单元测试。
 * 异常一律 {@link IllegalArgumentException}（消息即用户提示文案）。
 */
public final class ChapterSplitOps {

    /** 拆分方式。 */
    public enum Mode {
        /** 前后缀标记：前缀 + 数字（含汉字数字）+ 后缀，如「第…章」「Chapter …」。 */
        MARKER,
        /** 自定义正则：块文本命中即新章起点。 */
        REGEX,
        /** 每段字数：按块的纯文本长度攒段，攒满即收。 */
        LENGTH
    }

    /** 拆分参数。baseTitle 是原章节标题（无标记段的回退标题）。 */
    public record Params(Mode mode, String prefix, String suffix, String regex,
                         int length, String baseTitle) {
    }

    /** 一段拆分结果：章节标题 + 完整 XHTML。 */
    public record Segment(String title, String xhtml) {
    }

    /** 数字占位：阿拉伯数字（含全角）或汉字数字，用于前后缀标记模式。 */
    private static final String NUMBER = "(?:[0-9０-９]+|[一二三四五六七八九十百千零〇两]+)";

    /** 章节标题的最大展示长度；超出截断加省略号。 */
    private static final int TITLE_LIMIT = 40;

    private ChapterSplitOps() {
    }

    // ------------------------------------------------------------------ 拆分

    /**
     * 把章节 XHTML 拆成若干段；找不到可拆分处时返回单元素列表（调用方据此提示）。
     *
     * @throws IllegalArgumentException 参数不合法、XHTML 解析失败或正文为空
     */
    public static List<Segment> split(String xhtml, Params params) {
        if (params == null || params.mode() == null) {
            throw new IllegalArgumentException("请选择拆分方式");
        }
        Pattern pattern = switch (params.mode()) {
            case MARKER -> {
                // ⚠ 不能 trim：尾随空格是标记的一部分（如「Chapter 」+ 数字）
                String prefix = params.prefix() == null ? "" : params.prefix();
                String suffix = params.suffix() == null ? "" : params.suffix();
                if (prefix.isEmpty() && suffix.isEmpty()) {
                    throw new IllegalArgumentException("前缀与后缀不能同时为空");
                }
                yield Pattern.compile(Pattern.quote(prefix) + NUMBER + Pattern.quote(suffix));
            }
            case REGEX -> {
                try {
                    yield Pattern.compile(params.regex() == null ? "" : params.regex());
                } catch (PatternSyntaxException failed) {
                    throw new IllegalArgumentException("正则表达式无效：" + failed.getDescription());
                }
            }
            case LENGTH -> {
                if (params.length() < 1) {
                    throw new IllegalArgumentException("每段字数必须是不小于 1 的整数");
                }
                yield null;
            }
        };

        List<Node> blocks = topLevelBlocks(xhtml);
        List<String> texts = blocks.stream().map(ChapterSplitOps::blockText).toList();
        String baseTitle = params.baseTitle() == null || params.baseTitle().isBlank()
                ? "未命名章节" : params.baseTitle().trim();

        // splitAt[i] = true 表示第 i 块开启一个新段（第 0 块恒为新段起点）
        boolean[] splitAt = new boolean[blocks.size()];
        switch (params.mode()) {
            case MARKER, REGEX -> {
                for (int i = 0; i < blocks.size(); i++) {
                    if (pattern.matcher(texts.get(i)).find()) {
                        splitAt[i] = true;
                    }
                }
            }
            case LENGTH -> {
                int accumulated = 0;
                for (int i = 0; i < blocks.size(); i++) {
                    if (i > 0 && accumulated >= params.length()) {
                        splitAt[i] = true;
                        accumulated = 0;
                    }
                    accumulated += texts.get(i).length();
                }
            }
        }

        int firstMarker = -1;
        for (int i = 1; i < splitAt.length; i++) {
            if (splitAt[i]) {
                firstMarker = i;
                break;
            }
        }
        if (firstMarker < 0) {
            // 只有第 0 块这一个段起点＝拆不开
            return List.of(new Segment(baseTitle, assemble(xhtml, blocks, baseTitle)));
        }

        List<Segment> segments = new ArrayList<>();
        int start = 0;
        int index = 0;
        for (int i = 1; i <= splitAt.length; i++) {
            if (i < splitAt.length && !splitAt[i]) {
                continue;
            }
            String title;
            if (params.mode() == Mode.LENGTH) {
                title = index == 0 ? baseTitle : baseTitle + "（" + cn(index + 1) + "）";
            } else if (splitAt[start] && !texts.get(start).isEmpty()) {
                // 段首块自己就是标记命中（如「第一章 xxx」标题行），拿它当标题
                title = truncate(texts.get(start));
            } else {
                // 无标记的段（常见于首个标记之前的正文）回退到原章节标题
                title = index == 0 ? baseTitle : baseTitle + "（" + cn(index + 1) + "）";
            }
            segments.add(new Segment(title,
                    assemble(xhtml, blocks.subList(start, i), title)));
            start = i;
            index++;
        }
        return List.copyOf(segments);
    }

    // ------------------------------------------------------------------ 应用到书

    /**
     * 把拆分结果应用进书：原章节资源写入第 1 段，其余段各建一个新资源，目录项插在
     * 原章节**同级之后**，最后由内核按目录重排阅读顺序。
     *
     * <p>调用方须先拍撤销快照（{@code beginChange}）再调用本方法。
     *
     * @throws IllegalArgumentException 原章节不在目录里
     */
    public static void apply(Book book, TOCReference reference, List<Segment> segments) {
        TocEditor.Location location = TocEditor.locate(book, reference);
        if (location == null) {
            throw new IllegalArgumentException("该章节还没有加入目录，无法拆分");
        }
        Resource original = book.resources()
                .getByHref(org.chobit.epubra.lib.util.Hrefs
                        .resolve(book.contentDirectory(), reference.resourceHref()));
        if (original == null) {
            throw new IllegalArgumentException("章节资源不存在，无法拆分");
        }

        Segment first = segments.get(0);
        original.setString(first.xhtml());
        if (first.title() != null && !first.title().isBlank()
                && !first.title().equals(reference.title())) {
            reference.setTitle(first.title());
        }

        int naming = book.spine().size() + 1;
        for (int i = 1; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            String id = book.resources().uniqueId("chapter-" + naming);
            String href = book.resources()
                    .uniqueHref(book.contentDirectory() + "chapter-" + naming + ".xhtml");
            naming++;
            Resource chapter = new Resource(id, href, MediaTypes.XHTML);
            chapter.setString(segment.xhtml());
            book.resources().add(chapter);
            book.spine().addResourceId(id);
            TOCReference created = new TOCReference(
                    segment.title(), book.relativeToContentDirectory(href));
            location.siblings().add(location.index() + i, created);
        }
        TocEditor.syncSpineFromToc(book);
    }

    // ------------------------------------------------------------------ 内部

    /** 解析 XHTML 并取 body 的直接子元素；解析失败或正文为空都按参数错误处理。 */
    private static List<Node> topLevelBlocks(String xhtml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            // 不加载外部 DTD：XHTML 常带 1.0 过渡型 DOCTYPE，联网取 DTD 会卡死/失败（顺带防 XXE）
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new StringReader(xhtml)));
            NodeList bodyChildren = doc.getElementsByTagName("body").item(0).getChildNodes();
            List<Node> blocks = new ArrayList<>();
            for (int i = 0; i < bodyChildren.getLength(); i++) {
                Node child = bodyChildren.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    blocks.add(child);
                }
            }
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("章节没有正文内容，无法拆分");
            }
            return blocks;
        } catch (IllegalArgumentException userFacing) {
            throw userFacing;
        } catch (Exception failed) {
            throw new IllegalArgumentException("章节内容不是合法 XHTML，无法拆分");
        }
    }

    /** 块的纯文本：压平空白；标题匹配与字数统计都用它。 */
    private static String blockText(Node block) {
        return block.getTextContent() == null ? "" : block.getTextContent().replaceAll("\\s+", " ").trim();
    }

    /**
     * 用原文档的外壳（html 开标签 + head）包住一段块序列，产出完整 XHTML；
     * head 里的 {@code <title>} 换成本段标题。
     */
    private static String assemble(String source, List<Node> blocks, String title) {
        String htmlOpen = "<html xmlns=\"http://www.w3.org/1999/xhtml\">";
        int htmlAt = source.indexOf("<html");
        if (htmlAt >= 0) {
            int tagEnd = source.indexOf('>', htmlAt);
            if (tagEnd > htmlAt) {
                htmlOpen = source.substring(htmlAt, tagEnd + 1);
            }
        }
        String head = "<head><title>" + escape(title) + "</title></head>";
        int headStart = source.indexOf("<head");
        int headEnd = source.indexOf("</head>");
        if (headStart >= 0 && headEnd > headStart) {
            head = source.substring(headStart, headEnd + "</head>".length());
            String withTitle = TextSearch.replaceFirstTagText(head, "title", title);
            if (!withTitle.equals(head)) {
                head = withTitle;
            }
        }
        StringBuilder body = new StringBuilder();
        for (Node block : blocks) {
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(serialize(block));
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + htmlOpen + head + "<body>\n" + body + "\n</body>\n</html>\n";
    }

    /** 单个顶层块的 XML 文本；命名空间声明由 Transformer 补齐。 */
    private static String serialize(Node block) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(block), new StreamResult(out));
            return out.toString();
        } catch (Exception failed) {
            throw new IllegalArgumentException("章节内容无法序列化，无法拆分");
        }
    }

    private static String truncate(String text) {
        return text.length() <= TITLE_LIMIT ? text : text.substring(0, TITLE_LIMIT) + "…";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 一到九十九的汉字数字（段序号用）。 */
    private static String cn(int n) {
        String[] digit = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n <= 0 || n >= 100) {
            return String.valueOf(n);
        }
        if (n < 10) {
            return digit[n];
        }
        if (n == 10) {
            return "十";
        }
        if (n < 20) {
            return "十" + digit[n - 10];
        }
        String tens = digit[n / 10] + "十";
        int ones = n % 10;
        return ones == 0 ? tens : tens + digit[ones];
    }
}
