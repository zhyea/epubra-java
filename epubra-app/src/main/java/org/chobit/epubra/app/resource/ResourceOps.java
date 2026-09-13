package org.chobit.epubra.app.resource;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.util.Hrefs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 插图 / 资源相关的纯逻辑集合，不依赖 JavaFX：
 * <ul>
 *   <li>资源面板的行过滤（{@link #userVisible}）与删除前引用提示判定
 *       （{@link #isReferencedByChapters}）；</li>
 *   <li>编辑器工具条「图片」按钮的插图流水线：内容排重（{@link #findByContent}）、
 *       内容寻址命名（{@link #contentAddressedFileName}）、{@code <img>} 标签生成
 *       （{@link #buildInsertImageTag}）、多图片段拼接（{@link #joinInsertFragments}）、
 *       片段图片引用提取（{@link #extractImageSrcs}）。</li>
 * </ul>
 *
 * <p>UI 操作（选行、文件选择器、确认对话框等）在 {@code ResourceController} 里组装，
 * 这里只做判定与渲染，方便在单元测试里直接覆盖。最初只为资源面板服务，
 * P1/P2 轮次后编辑器工具条的插图链路也走这里。
 */
public final class ResourceOps {

    private ResourceOps() {
    }

    /**
     * 过滤出可以展示给用户看的资源：剔除 nav / NCX —— 它们由写出流程自动维护，
     * 让用户编辑反而会破坏一致性。
     */
    public static List<Resource> userVisible(Book book) {
        Resource nav = book.navResource();
        List<Resource> rows = new ArrayList<>();
        for (Resource resource : book.resources().all()) {
            if (resource == nav || resource.isNavDocument() || MediaTypes.NCX.equals(resource.mediaType())) {
                continue;
            }
            rows.add(resource);
        }
        return rows;
    }

    /**
     * 通过文件名粗略判断资源是否被任意章节的 XHTML 提到。
     *
     * <p>这是为了删除前的提示，不要求精确：full-reference 走
     * {@link Book#unreferencedResources()}。
     *
     * <p><b>必须同时比对原文件名与 XML 转义后的文件名</b>：{@link #buildInsertImageTag}
     * 写 {@code src} / {@code alt} 时会做 XML 转义，磁盘上的 {@code Tom & Jerry.png}
     * 在正文里是 {@code Tom &amp; Jerry.png}。只比对原名会漏判——删除这张被引用的图时，
     * 用户就看不到「正文中存在引用」的提示。
     */
    public static boolean isReferencedByChapters(Book book, Resource resource) {
        if (book == null || resource == null) {
            return false;
        }
        String fileName = resource.fileName();
        if (fileName.isEmpty()) {
            return false;
        }
        String escaped = escapeXmlAttribute(fileName);
        for (Resource chapter : book.spineResources()) {
            if (chapter == resource) {
                continue;
            }
            String text = chapter.asString();
            if (text.contains(fileName) || text.contains(escaped)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在书里找一份与给定<b>字节内容完全一致</b>的既有资源，找不到返回 {@code null}。
     *
     * <p>用于插入图片前的去重（#49 之后的口径）：书里已有同内容图片时直接复用、
     * 不再重复挂载——反复选同一张图（哪怕换了个文件名）不会让资源列表堆满副本。
     *
     * <p>直接 {@link Arrays#equals(byte[], byte[])} 逐字节比较，而不是比 md5：
     * 排重要的是零碰撞，md5 只在<b>命名</b>（{@link #contentAddressedFileName}）里用。
     * 旧版 books 里按原名挂载的同内容图片也会被这里认出来并复用。
     */
    public static Resource findByContent(Book book, byte[] data) {
        if (book == null || book.resources() == null || data == null || data.length == 0) {
            return null;
        }
        for (Resource resource : book.resources().all()) {
            if (Arrays.equals(data, resource.data())) {
                return resource;
            }
        }
        return null;
    }

    /**
     * 内容寻址命名：{@code <md5 十六进制><原扩展名>}，如 {@code 5d41402abc4b2a76b9719d911017c592.png}。
     *
     * <p>插图不再用图片原名（可能带中文 / 空格 / 特殊字符，还会把用户本地目录结构泄进包里）。
     * {@link Book#addResource(String, byte[])} 会按媒体类型把图片归到 {@code images/} 子目录，
     * 因此最终 href 形如 {@code OEBPS/images/<md5>.png}。
     *
     * <p>内容寻址天然与 {@link #findByContent} 联动：同内容必同名，重名改名逻辑
     * （{@code uniqueHref}）对新插图永远不触发；同一批选两张相同的图也只挂一份资源。
     * 扩展名取自原文件名——媒体类型识别与阅读器渲染都靠它，不能省。
     */
    public static String contentAddressedFileName(String originalName, byte[] data) {
        String ext = "";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                ext = originalName.substring(dot);
            }
        }
        return md5Hex(data) + ext;
    }

    /** MD5 摘要的小写十六进制串（命名去标识用，非安全场景）。 */
    public static String md5Hex(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest.digest(data)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 MD5 实现", impossible);
        }
    }

    /**
     * 从插入片段里抽出所有 {@code <img src="…">} 的 src 属性值（XML 转义已还原）。
     *
     * <p>用于插图前把目标资源补写进预览镜像（见 {@code PreviewMirror#mirrorResource}）：
     * 可视化编辑器 {@code loadContent} 没有解析基准，相对引用全靠镜像 + {@code <base>}，
     * 新插入的图不在「章节可达」范围内，不补写就会是裂图。
     *
     * <p>片段是本应用自己生成的（{@link #buildInsertImageTag}），属性恒为双引号；
     * 正则在这里够用且可无头单测，不必动用 DOM 解析。
     */
    public static List<String> extractImageSrcs(String xhtml) {
        if (xhtml == null || xhtml.isEmpty()) {
            return List.of();
        }
        List<String> srcs = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*\"([^\"]*)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xhtml);
        while (matcher.find()) {
            srcs.add(unescapeXmlAttribute(matcher.group(1)));
        }
        return srcs;
    }

    /** {@link #escapeXmlAttribute} 的逆运算，只处理自己会写的五个实体。 */
    private static String unescapeXmlAttribute(String escaped) {
        return escaped.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /**
     * 把多个待插入片段连成一段可插入的 XHTML。
     *
     * <p><b>不能裸连</b>：{@code <img/><img/>} 只是两个相邻的行内元素，渲染出来会挤在同一行，
     * 看起来像一张被压扁的图。中间插 {@code <br/>} 分隔——{@code <br/>} 在 {@code <p>} 之内
     * 之外都是合法 XHTML；改用 {@code <p>} 包裹虽然「语义更像段落」，但插入点常位于某个
     * {@code <p>} 内部，会造出非法的嵌套 {@code <p>}。
     */
    public static String joinInsertFragments(List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return "";
        }
        if (fragments.size() == 1) {
            return fragments.get(0);
        }
        return String.join("<br/>", fragments);
    }

    /**
     * 生成本地引用图片的 {@code <img>} 标签：相对路径相对于章节所在目录。
     *
     * <p>用 {@link Hrefs#relativePath(String, String)} 而不是只做前缀剥离的
     * {@link Hrefs#relativize(String, String)}：外部 EPUB 的章节常位于 {@code OEBPS/text/} 这类
     * 子目录，图片却在 {@code OEBPS/images/}，此时必须产出 {@code ../images/a.png}；
     * 前缀剥离会写出 {@code OEBPS/images/a.png} 这条包内绝对路径，预览与阅读器都解析不到，
     * 结构校验还会判为断链。
     *
     * @param chapterHref 章节 href，用于计算相对路径
     * @param imageHref    图片 href
     * @param alt          作为 alt 文本
     * @return 形如 {@code <img src="../images/foo.png" alt="foo.png"/>}
     */
    public static String buildInsertImageTag(String chapterHref, String imageHref, String alt) {
        String chapterDir = Hrefs.parentDirectory(chapterHref);
        String relative = Hrefs.relativePath(chapterDir, imageHref);
        return String.format("<img src=\"%s\" alt=\"%s\"/>",
                escapeXmlAttribute(relative), escapeXmlAttribute(alt));
    }

    /**
     * 转义 XML 属性值里不能裸写的字符。
     *
     * <p>必须做：文件名属于用户数据，从本机选图时完全可能出现 {@code Tom & Jerry.png}
     * 这类名字。不转义会拼出非法 XHTML —— 写进正文后整章解析失败，还会被结构校验
     * 判为文档损坏。
     */
    private static String escapeXmlAttribute(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
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
}
