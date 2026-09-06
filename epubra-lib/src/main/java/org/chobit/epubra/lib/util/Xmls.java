package org.chobit.epubra.lib.util;

import org.chobit.epubra.lib.EpubException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 DOM 工具：解析时关闭外部实体与 DTD 加载，避免 EPUB 中的恶意实体（XXE）。
 */
public final class Xmls {

    public static final String NS_OPF = "http://www.idpf.org/2007/opf";
    public static final String NS_DC = "http://purl.org/dc/elements/1.1/";
    public static final String NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container";
    public static final String NS_NCX = "http://www.daisy.org/z3986/2005/ncx/";
    public static final String NS_XHTML = "http://www.w3.org/1999/xhtml";
    public static final String NS_EPUB = "http://www.idpf.org/2007/ops";

    private Xmls() {
    }

    public static Document parse(byte[] data) {
        if (data == null || data.length == 0) {
            throw new EpubException("XML 内容为空");
        }
        try {
            DocumentBuilder builder = builder();
            return builder.parse(new InputSource(new ByteArrayInputStream(data)));
        } catch (SAXException | IOException e) {
            throw new EpubException("XML 解析失败：" + e.getMessage(), e);
        }
    }

    public static Document parse(String xml) {
        try {
            return builder().parse(new InputSource(new StringReader(xml)));
        } catch (SAXException | IOException e) {
            throw new EpubException("XML 解析失败：" + e.getMessage(), e);
        }
    }

    private static DocumentBuilder builder() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setExpandEntityReferences(false);
        try {
            // 允许 DOCTYPE（大量 EPUB 仍带 XHTML 1.1 DTD），但禁止任何外部实体解析
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException ignored) {
            // 不支持的特性直接跳过，安全默认值已足够
        }
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder;
        } catch (ParserConfigurationException e) {
            throw new EpubException("无法创建 XML 解析器", e);
        }
    }

    /** 直接子元素中所有元素节点。 */
    public static List<Element> children(Element parent) {
        List<Element> list = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                list.add((Element) node);
            }
        }
        return list;
    }

    /** 按本地名匹配直接子元素，忽略命名空间。 */
    public static List<Element> children(Element parent, String localName) {
        List<Element> list = new ArrayList<>();
        for (Element child : children(parent)) {
            if (localName.equals(localName(child))) {
                list.add(child);
            }
        }
        return list;
    }

    public static Element child(Element parent, String localName) {
        List<Element> matched = children(parent, localName);
        return matched.isEmpty() ? null : matched.get(0);
    }

    /** 深度优先查找所有匹配本地名的后代元素。 */
    public static List<Element> descendants(Element root, String localName) {
        List<Element> list = new ArrayList<>();
        collect(root, localName, list);
        return list;
    }

    private static void collect(Element current, String localName, List<Element> sink) {
        for (Element child : children(current)) {
            if (localName.equals(localName(child))) {
                sink.add(child);
            }
            collect(child, localName, sink);
        }
    }

    public static String localName(Element element) {
        String local = element.getLocalName();
        return local != null ? local : element.getNodeName();
    }

    /** 元素的全部后代文本（去掉标签）。 */
    public static String textOf(Element element) {
        if (element == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        walkText(element, sb);
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    private static void walkText(Node node, StringBuilder sb) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            sb.append(node.getNodeValue());
            return;
        }
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            walkText(nodes.item(i), sb);
        }
    }

    /** 取直接子元素的文本，不存在时返回 null。 */
    public static String childText(Element parent, String localName) {
        Element child = child(parent, localName);
        return child == null ? null : textOf(child);
    }

    /** 判断元素是否带有指定类型的 nav（EPUB 3 的 epub:type 属性）。 */
    public static boolean hasEpubType(Element element, String type) {
        String attr = element.getAttributeNS(NS_EPUB, "type");
        if (attr.isBlank()) {
            attr = element.getAttribute("epub:type");
        }
        return !attr.isBlank() && attr.contains(type);
    }
}
