package org.chobit.epubra.lib.util;

import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.Resources;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用提取（{@link ResourceReferences}）的验证：DOM 主路径、正则回退、路径解析与外部链接跳过。
 */
class ResourceReferencesTest {

    private static Resource xhtml(String href, String body) {
        String document = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink">
                <head><title>t</title></head>
                <body>
                %s
                </body>
                </html>
                """.formatted(body);
        return new Resource("r1", href, MediaTypes.XHTML, document.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<String> targets(Resource resource) {
        return ResourceReferences.extract(resource).references().stream()
                .map(ResourceReferences.Reference::rawTarget)
                .toList();
    }

    @Test
    void 应抽取img的src与srcset() {
        Resource resource = xhtml("OEBPS/chapter-1.xhtml",
                "<img src=\"images/a.png\"/>"
                        + "<img srcset=\"images/b.png 1x, images/c.png 2x\" src=\"images/fallback.png\"/>");
        List<String> targets = targets(resource);
        assertTrue(targets.contains("images/a.png"));
        assertTrue(targets.contains("images/b.png"), "srcset 的第一段应被抽取");
        assertTrue(targets.contains("images/c.png"), "srcset 的第二段应被抽取");
        assertTrue(targets.contains("images/fallback.png"));
    }

    @Test
    void 应抽取link与a的href() {
        Resource resource = xhtml("OEBPS/chapter-1.xhtml",
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"styles/main.css\"/>"
                        + "<a href=\"chapter-2.xhtml\">下一章</a>"
                        + "<a href=\"#section-1\">同页锚点</a>"
                        + "<a href=\"https://example.com/x\">外链</a>");
        List<String> targets = targets(resource);
        assertTrue(targets.contains("styles/main.css"));
        assertTrue(targets.contains("chapter-2.xhtml"));
        assertTrue(targets.contains("#section-1"));
        assertTrue(targets.contains("https://example.com/x"));
    }

    @Test
    void 应抽取svg的xlinkHref与内联style() {
        Resource resource = xhtml("OEBPS/chapter-1.xhtml",
                "<svg><use xlink:href=\"images/icon.svg#glyph\"/>"
                        + "<image xlink:href=\"images/pic.png\"/></svg>"
                        + "<div style=\"background: url(images/bg.png)\"></div>");
        List<String> targets = targets(resource);
        assertTrue(targets.contains("images/icon.svg#glyph"));
        assertTrue(targets.contains("images/pic.png"));
        assertTrue(targets.contains("images/bg.png"), "内联 style 中的 url() 应被抽取");
    }

    @Test
    void 应抽取style元素与CSS资源中的url与import() {
        Resource page = xhtml("OEBPS/chapter-1.xhtml",
                "<style>@import \"other.css\"; body { background: url(images/bg.png); }</style>");
        List<String> pageTargets = targets(page);
        assertTrue(pageTargets.contains("other.css"));
        assertTrue(pageTargets.contains("images/bg.png"));

        Resource css = new Resource("css1", "OEBPS/styles/main.css", MediaTypes.CSS,
                ("@import url(reset.css);\n"
                        + "@font-face { src: url(fonts/demo.woff2) format('woff2'); }\n"
                        + "/* 注释里的 url(images/ignored.png) 不应被抽取 */\n"
                        + ".a { background: url('../images/x.png'); }")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> cssTargets = targets(css);
        assertTrue(cssTargets.contains("reset.css"));
        assertTrue(cssTargets.contains("fonts/demo.woff2"));
        assertTrue(cssTargets.contains("../images/x.png"));
        assertFalse(cssTargets.contains("images/ignored.png"), "注释中的内容不应被当成引用");
    }

    @Test
    void 非良构文档应回退到正则且标记wellFormed为false() {
        Resource resource = new Resource("bad", "OEBPS/bad.xhtml", MediaTypes.XHTML,
                ("<html><body><p>未闭合的 & 与 < 符号</p>"
                        + "<img src=\"images/a.png\"/>"
                        + "<link href=\"styles/main.css\"/></body></html>")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ResourceReferences.Extraction extraction = ResourceReferences.extract(resource);
        assertFalse(extraction.wellFormed(), "解析失败应走正则回退");
        List<String> targets = extraction.references().stream()
                .map(ResourceReferences.Reference::rawTarget)
                .toList();
        assertTrue(targets.contains("images/a.png"));
        assertTrue(targets.contains("styles/main.css"));
    }

    @Test
    void 良构文档应标记wellFormed为true() {
        ResourceReferences.Extraction extraction =
                ResourceReferences.extract(xhtml("OEBPS/chapter-1.xhtml", "<p>正文</p>"));
        assertTrue(extraction.wellFormed());
    }

    @Test
    void 图片与字体等非文本资源不产出引用() {
        Resource png = new Resource("p1", "OEBPS/images/a.png", MediaTypes.PNG, new byte[]{1, 2, 3});
        ResourceReferences.Extraction extraction = ResourceReferences.extract(png);
        assertTrue(extraction.references().isEmpty());
        assertTrue(extraction.wellFormed());
    }

    @Test
    void resolveTarget应把相对路径解析为容器内绝对路径() {
        assertEquals("OEBPS/images/a.png", ResourceReferences.resolveTarget("OEBPS/", "images/a.png"));
        assertEquals("OEBPS/images/a.png", ResourceReferences.resolveTarget("OEBPS/", "./images/a.png"));
        assertEquals("images/a.png", ResourceReferences.resolveTarget("OEBPS/", "../images/a.png"));
        assertEquals("a.png", ResourceReferences.resolveTarget("", "a.png"));
        assertNull(ResourceReferences.resolveTarget("OEBPS/", ""));
        assertNull(ResourceReferences.resolveTarget("OEBPS/", null));
    }

    @Test
    void resolveTarget应跳过外部链接与纯锚点() {
        assertNull(ResourceReferences.resolveTarget("OEBPS/", "https://example.com/a.png"));
        assertNull(ResourceReferences.resolveTarget("OEBPS/", "//cdn.example.com/a.png"));
        assertNull(ResourceReferences.resolveTarget("OEBPS/", "mailto:a@b.com"));
        assertNull(ResourceReferences.resolveTarget("OEBPS/", "data:image/png;base64,AAAA"));
        assertNull(ResourceReferences.resolveTarget("OEBPS/", "#section-1"));
        assertTrue(ResourceReferences.isExternal("tel:+8613800000000"));
        assertTrue(ResourceReferences.isExternal("javascript:void(0)"));
        assertFalse(ResourceReferences.isExternal("images/a.png"));
        assertFalse(ResourceReferences.isExternal("./a.png"));
    }

    @Test
    void 百分号编码应被解码且保留加号() {
        assertEquals("OEBPS/images/图 1.png",
                ResourceReferences.percentDecode("OEBPS/images/%E5%9B%BE%201.png"));
        assertEquals("a+b.png", ResourceReferences.percentDecode("a+b.png"), "加号不应被转成空格");
        assertEquals("plain.png", ResourceReferences.percentDecode("plain.png"));
        assertEquals("", ResourceReferences.percentDecode(null));
    }

    @Test
    void fragmentIds应收集文档内全部id() {
        Resource resource = xhtml("OEBPS/chapter-1.xhtml",
                "<h1 id=\"top\">标题</h1><p id=\"p-1\">正文</p><div><span id=\"deep\">深层</span></div>");
        Set<String> ids = ResourceReferences.fragmentIds(resource);
        assertTrue(ids.contains("top"));
        assertTrue(ids.contains("p-1"));
        assertTrue(ids.contains("deep"));
    }

    @Test
    void fragmentIds在解析失败时返回空集合() {
        Resource resource = new Resource("bad", "OEBPS/bad.xhtml", MediaTypes.XHTML,
                "<html><p>& 未转义</p></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(ResourceReferences.fragmentIds(resource).isEmpty());
    }

    @Test
    void escapesContainer应识别越出根目录的引用() {
        assertFalse(ResourceReferences.escapesContainer("OEBPS/", "../images/a.png"),
                "从 OEBPS/ 上跳一级仍在容器内");
        assertTrue(ResourceReferences.escapesContainer("OEBPS/", "../../a.png"));
        assertFalse(ResourceReferences.escapesContainer("OEBPS/text/", "../../images/a.png"));
        assertTrue(ResourceReferences.escapesContainer("", "../a.png"));
    }

    @Test
    void findResource应支持三连回退() {
        Resources resources = new Resources();
        Resource target = new Resource("x", "OEBPS/images/图 1.png", MediaTypes.PNG, new byte[]{1});
        resources.add(target);

        ResourceReferences.Lookup exact =
                ResourceReferences.findResource(resources, "OEBPS/images/图 1.png");
        assertNotNull(exact.resource());
        assertFalse(exact.caseMismatch());

        ResourceReferences.Lookup decoded =
                ResourceReferences.findResource(resources, "OEBPS/images/%E5%9B%BE%201.png");
        assertNotNull(decoded.resource(), "百分号编码的路径应能命中");

        ResourceReferences.Lookup ignoringCase =
                ResourceReferences.findResource(resources, "OEBPS/IMAGES/图 1.png");
        assertNotNull(ignoringCase.resource(), "大小写不同也应命中");
        assertTrue(ignoringCase.caseMismatch());

        assertNull(ResourceReferences.findResource(resources, "OEBPS/images/missing.png").resource());
    }
}
