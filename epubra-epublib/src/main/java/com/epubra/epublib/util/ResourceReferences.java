package com.epubra.epublib.util;

import com.epubra.epublib.domain.MediaTypes;
import com.epubra.epublib.domain.Resource;
import com.epubra.epublib.domain.Resources;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XHTML / SVG / CSS 中的 URI 引用提取，只使用 JDK（{@code javax.xml} + {@code java.util.regex}）。
 *
 * <p>放在 {@code util} 而不是 {@code validation} 包，是因为 {@code domain.Book} 也要用它做
 * 「未引用资源」判定；放 validation 会造成 domain → validation 的反向依赖。
 *
 * <p>本类全程只读，不修改任何 {@link Resource#data()}。
 */
public final class ResourceReferences {

    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    /** 各元素上承载 URI 引用的属性名（按元素本地名索引）。 */
    private static final Map<String, List<String>> REFERENCE_ATTRIBUTES = referenceAttributes();

    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*['\"]?([^'\"()]*)['\"]?\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_IMPORT = Pattern.compile(
            "@import\\s+(?:url\\(\\s*['\"]?([^'\"()]*)['\"]?\\s*\\)|['\"]([^'\"]+)['\"])",
            Pattern.CASE_INSENSITIVE);
    /** 非良构文档的正则回退：只认 src / href / data 三种属性，且必须落在引号内。 */
    private static final Pattern FALLBACK_ATTRIBUTE = Pattern.compile(
            "(?:src|href|data)\\s*=\\s*(['\"])([^'\"]*)\\1",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*:");

    private static final Set<String> EXTERNAL_SCHEMES = Set.of(
            "http", "https", "ftp", "ftps", "mailto", "tel", "data", "blob", "javascript", "file", "urn");

    private ResourceReferences() {
    }

    /**
     * 引用来源描述。
     *
     * @param sourceHref 引用所在的资源在容器内的路径
     * @param context    引用出处，形如 {@code "img/@src"}、{@code "css/url()"}、{@code "style/url()"}
     * @param rawTarget  原始目标串（未解码、未解析）
     */
    public record Reference(String sourceHref, String context, String rawTarget) {
    }

    /**
     * 引用提取结果。
     *
     * @param references 引用列表
     * @param wellFormed 文档是否为良构 XML；{@code false} 表示走了正则回退或解析失败
     */
    public record Extraction(List<Reference> references, boolean wellFormed) {
    }

    /**
     * 在资源集合中查找目标的结果。
     *
     * @param resource      命中的资源，未命中为 {@code null}
     * @param matchedHref   实际命中的 href（大小写不敏感命中时与传入路径不同）
     * @param caseMismatch  是否只在忽略大小写时才命中（ZIP 与 Windows 文件系统大小写不敏感，不算问题）
     */
    public record Lookup(Resource resource, String matchedHref, boolean caseMismatch) {
    }

    /**
     * 主入口。XHTML / SVG 先走 DOM，解析失败则正则回退且 {@code wellFormed=false}；
     * CSS 走 {@code url(...)} / {@code @import} 正则；其余媒体类型返回空列表且 {@code wellFormed=true}。
     *
     * @param resource 待扫描的资源，允许为 {@code null}
     * @return 提取结果，永不为 {@code null}
     */
    public static Extraction extract(Resource resource) {
        if (resource == null) {
            return new Extraction(List.of(), true);
        }
        String mediaType = resource.mediaType();
        if (MediaTypes.CSS.equals(mediaType)) {
            List<Reference> references = new ArrayList<>();
            for (String target : urlReferences(resource.asString())) {
                references.add(new Reference(resource.href(), "css/url()", target));
            }
            return new Extraction(List.copyOf(references), true);
        }
        if (!MediaTypes.XHTML.equals(mediaType) && !MediaTypes.SVG.equals(mediaType)) {
            return new Extraction(List.of(), true);
        }
        byte[] data = resource.data();
        try {
            Document document = Xmls.parse(data);
            List<Reference> references = collectFromDocument(document, resource.href());
            return new Extraction(List.copyOf(references), true);
        } catch (RuntimeException e) {
            List<Reference> references = collectByRegex(resource.asString(), resource.href());
            return new Extraction(List.copyOf(references), false);
        }
    }

    /**
     * 把原始目标解析为容器内路径。
     *
     * <p>外部引用（{@code http} / {@code mailto} / {@code data} / 协议相对等）与空串返回 {@code null}，
     * 纯片断引用（{@code "#id"}）同样返回 {@code null}——它应由调用方按「同文档锚点」单独处理。
     *
     * @param baseDir   引用所在资源的目录，用 {@link Hrefs#parentDirectory(String)} 得到
     * @param rawTarget 原始目标串
     * @return 容器内的绝对路径，无法解析时返回 {@code null}
     */
    public static String resolveTarget(String baseDir, String rawTarget) {
        if (rawTarget == null) {
            return null;
        }
        String target = rawTarget.trim();
        if (target.isEmpty() || isExternal(target) || target.startsWith("#")) {
            return null;
        }
        return normalizePath(baseDir, target);
    }

    /**
     * 把 baseDir 与相对引用拼成容器内绝对路径，保留片断标识符。
     *
     * <p>这里没有直接复用 {@link Hrefs#resolve(String, String)}：后者在 {@code ..} 需要弹出
     * 「第一个」路径段时会把它残留下来（{@code OEBPS/ + ../a.png} 得到 {@code OEBPS/a.png}，
     * 正确结果应为 {@code a.png}），会让跨目录引用的解析结果偏深一格，进而误报断链。
     * 该方法只服务于新增的引用解析，不改动 {@link Hrefs} 以免波及读写链路。
     */
    private static String normalizePath(String baseDir, String target) {
        String fragment = "";
        int hash = target.indexOf('#');
        if (hash >= 0) {
            fragment = target.substring(hash);
            target = target.substring(0, hash);
        }
        String base = baseDir == null ? "" : baseDir.replace('\\', '/');
        String path = target.replace('\\', '/');

        List<String> segments = new ArrayList<>();
        if (path.startsWith("/")) {
            path = path.substring(1);
        } else {
            appendSegments(segments, base);
        }
        appendSegments(segments, path);
        return String.join("/", segments) + fragment;
    }

    private static void appendSegments(List<String> segments, String path) {
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
                continue;
            }
            segments.add(part);
        }
    }

    /** 是否为应当跳过的外部引用（含协议相对地址、片断锚点与空串）。 */
    public static boolean isExternal(String rawTarget) {
        if (rawTarget == null) {
            return true;
        }
        String target = rawTarget.trim();
        if (target.isEmpty() || target.startsWith("#") || target.startsWith("//")) {
            return true;
        }
        Matcher matcher = URI_SCHEME.matcher(target);
        if (!matcher.find()) {
            return false;
        }
        String scheme = target.substring(0, matcher.end() - 1).toLowerCase(Locale.ROOT);
        return EXTERNAL_SCHEMES.contains(scheme);
    }

    /**
     * 只解码 {@code %XX}，不做 {@code '+'} → 空格 转换
     * （{@code URLDecoder} 会把路径里合法的 {@code '+'} 吃掉，造成误报）。
     */
    public static String percentDecode(String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text == null ? "" : text;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '%' && index + 2 < text.length()) {
                int high = Character.digit(text.charAt(index + 1), 16);
                int low = Character.digit(text.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) | low);
                    index += 3;
                    continue;
                }
            }
            out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            index++;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 抽取 CSS 文本中的 {@code url(...)} 与 {@code @import} 目标。
     *
     * <p>先剥掉注释，避免注释里出现的示例路径被当成真实引用。
     */
    public static List<String> urlReferences(String cssText) {
        if (cssText == null || cssText.isEmpty()) {
            return List.of();
        }
        String clean = CSS_COMMENT.matcher(cssText).replaceAll(" ");
        Set<String> targets = new LinkedHashSet<>();
        Matcher url = CSS_URL.matcher(clean);
        while (url.find()) {
            addTarget(targets, url.group(1));
        }
        Matcher imported = CSS_IMPORT.matcher(clean);
        while (imported.find()) {
            addTarget(targets, imported.group(1) != null ? imported.group(1) : imported.group(2));
        }
        return List.copyOf(targets);
    }

    /** 文档内全部 {@code id} 属性值；解析失败返回空 Set。 */
    public static Set<String> fragmentIds(Resource resource) {
        if (resource == null) {
            return Set.of();
        }
        byte[] data = resource.data();
        if (data == null || data.length == 0) {
            return Set.of();
        }
        try {
            Document document = Xmls.parse(data);
            Set<String> ids = new HashSet<>();
            collectIds(document.getDocumentElement(), ids);
            return Set.copyOf(ids);
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /**
     * 资源查找三连回退：原样查 → 百分号解码后查 → 忽略大小写遍历比较。
     *
     * <p>第三次命中不算问题，只在 {@code caseMismatch} 里标注「路径大小写与清单不一致」。
     */
    public static Lookup findResource(Resources resources, String containerPath) {
        if (resources == null || containerPath == null || containerPath.isEmpty()) {
            return new Lookup(null, null, false);
        }
        Resource direct = resources.getByHref(containerPath);
        if (direct != null) {
            return new Lookup(direct, containerPath, false);
        }
        String decoded = percentDecode(containerPath);
        if (!decoded.equals(containerPath)) {
            Resource byDecoded = resources.getByHref(decoded);
            if (byDecoded != null) {
                return new Lookup(byDecoded, decoded, false);
            }
        }
        for (Resource candidate : resources.all()) {
            if (candidate.href() != null && candidate.href().equalsIgnoreCase(containerPath)) {
                return new Lookup(candidate, candidate.href(), true);
            }
        }
        return new Lookup(null, null, false);
    }

    /** 判断引用是否逃出容器根目录（{@code Hrefs.resolve} 会消解 {@code ..}，因此需单独统计上跳层数）。 */
    public static boolean escapesContainer(String baseDir, String rawTarget) {
        if (rawTarget == null) {
            return false;
        }
        String target = rawTarget.trim();
        if (target.isEmpty() || isExternal(target)) {
            return false;
        }
        int hash = target.indexOf('#');
        if (hash >= 0) {
            target = target.substring(0, hash);
        }
        if (target.isEmpty()) {
            return false;
        }
        int upward = 0;
        String rest = target.replace('\\', '/');
        while (rest.startsWith("../")) {
            upward++;
            rest = rest.substring(3);
        }
        if (rest.equals("..")) {
            upward++;
        }
        return upward > countSegments(baseDir);
    }

    private static int countSegments(String baseDir) {
        if (baseDir == null || baseDir.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String part : baseDir.replace('\\', '/').split("/")) {
            if (!part.isEmpty() && !".".equals(part)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ DOM 抽取

    private static List<Reference> collectFromDocument(Document document, String sourceHref) {
        List<Reference> references = new ArrayList<>();
        if (document == null || document.getDocumentElement() == null) {
            return references;
        }
        collectFromElement(document.getDocumentElement(), sourceHref, references);
        return references;
    }

    private static void collectFromElement(Element element, String sourceHref, List<Reference> sink) {
        String localName = Xmls.localName(element).toLowerCase(Locale.ROOT);

        for (String attribute : REFERENCE_ATTRIBUTES.getOrDefault(localName, List.of())) {
            String value = attributeValue(element, attribute);
            if (value == null || value.isBlank()) {
                continue;
            }
            if ("srcset".equals(attribute)) {
                for (String candidate : splitSrcSet(value)) {
                    sink.add(new Reference(sourceHref, localName + "/@srcset", candidate));
                }
            } else {
                sink.add(new Reference(sourceHref, localName + "/@" + attribute, value.trim()));
            }
        }

        String inlineStyle = element.getAttribute("style");
        if (inlineStyle != null && !inlineStyle.isBlank()) {
            for (String target : urlReferences(inlineStyle)) {
                sink.add(new Reference(sourceHref, "style/url()", target));
            }
        }

        if ("style".equals(localName)) {
            String css = rawText(element);
            for (String target : urlReferences(css)) {
                sink.add(new Reference(sourceHref, "style-element/url()", target));
            }
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                collectFromElement((Element) child, sourceHref, sink);
            }
        }
    }

    /** {@code xlink:href} 兼顾命名空间写法与普通属性名写法。 */
    private static String attributeValue(Element element, String attribute) {
        if ("xlink:href".equals(attribute)) {
            String value = element.getAttributeNS(XLINK_NS, "href");
            if (value == null || value.isBlank()) {
                value = element.getAttribute("xlink:href");
            }
            return value;
        }
        return element.getAttribute(attribute);
    }

    /** {@code srcset} 按逗号切分，取每段首个空白之前的 token。 */
    private static List<String> splitSrcSet(String value) {
        List<String> tokens = new ArrayList<>();
        for (String segment : value.split(",")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            String url = space < 0 ? trimmed : trimmed.substring(0, space);
            if (!url.isEmpty()) {
                tokens.add(url);
            }
        }
        return tokens;
    }

    private static String rawText(Element element) {
        StringBuilder sb = new StringBuilder();
        collectRawText(element, sb);
        return sb.toString();
    }

    private static void collectRawText(Node node, StringBuilder sink) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            sink.append(node.getNodeValue());
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectRawText(children.item(i), sink);
        }
    }

    private static void collectIds(Element element, Set<String> sink) {
        String id = element.getAttribute("id");
        if (id != null && !id.isBlank()) {
            sink.add(id.trim());
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                collectIds((Element) child, sink);
            }
        }
    }

    // ------------------------------------------------------------------ 正则回退

    private static List<Reference> collectByRegex(String markup, String sourceHref) {
        List<Reference> references = new ArrayList<>();
        if (markup == null || markup.isEmpty()) {
            return references;
        }
        String clean = CSS_COMMENT.matcher(markup).replaceAll(" ");
        Matcher matcher = FALLBACK_ATTRIBUTE.matcher(clean);
        while (matcher.find()) {
            String value = matcher.group(2).trim();
            if (!value.isEmpty()) {
                references.add(new Reference(sourceHref, "regex/@uri", value));
            }
        }
        for (String target : urlReferences(clean)) {
            references.add(new Reference(sourceHref, "regex/url()", target));
        }
        return references;
    }

    private static void addTarget(Set<String> sink, String raw) {
        if (raw == null) {
            return;
        }
        String target = raw.trim();
        if (target.isEmpty() || target.startsWith("#")) {
            return;
        }
        sink.add(target);
    }

    private static Map<String, List<String>> referenceAttributes() {
        Map<String, List<String>> map = new HashMap<>();
        map.put("img", List.of("src", "srcset"));
        map.put("a", List.of("href"));
        map.put("area", List.of("href"));
        map.put("link", List.of("href"));
        map.put("script", List.of("src"));
        map.put("object", List.of("data"));
        map.put("source", List.of("src"));
        map.put("video", List.of("src"));
        map.put("audio", List.of("src"));
        map.put("track", List.of("src"));
        map.put("iframe", List.of("src"));
        map.put("embed", List.of("src"));
        map.put("image", List.of("xlink:href", "href"));
        map.put("use", List.of("xlink:href", "href"));
        map.put("feimage", List.of("xlink:href", "href"));
        return Map.copyOf(map);
    }
}
