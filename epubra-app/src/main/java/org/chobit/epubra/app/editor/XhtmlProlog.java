package org.chobit.epubra.app.editor;

/**
 * XHTML 字符串层的 XML 声明消毒。
 *
 * <p>XML 声明（{@code <?xml ... ?>}）不在 DOM 里，可视化编辑器序列化（{@code serialize()}
 * 恒定前置规范声明）不可能产出坏声明——污染只可能来自字符串层操作往源码区插片段
 * （2026-09-15 实测：{@code <u></u>} 被插进声明，得到
 * {@code <?xml version="1.0" encoding="UTF-8<u></u>"?>}，解析器报
 * 「[Fatal Error] :1:46 编码名称无效」）。坏声明交给任何 XML 解析器都是 Fatal Error，
 * 所以在内容进入解析器之前先做字符串级修复。
 */
public final class XhtmlProlog {

    private static final String CANONICAL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    private XhtmlProlog() {
    }

    /**
     * 修复被污染的 XML 声明；声明合法或不存在时原样返回。
     *
     * <p>只重写声明本身，其余内容（含声明后的换行与空白）一律不动——修复必须比破坏
     * 更保守，否则消毒器自己就成了新的数据损失来源。
     */
    public static String repairProlog(String xhtml) {
        if (xhtml == null || !xhtml.startsWith("<?xml")) {
            return xhtml;
        }
        int end = xhtml.indexOf("?>");
        if (end < 0) {
            // 残缺声明（没有闭合）：整行丢弃（残余留在正文里会变成可见垃圾文本），补规范声明
            int nl = xhtml.indexOf('\n');
            return nl >= 0 ? CANONICAL + xhtml.substring(nl) : CANONICAL + "\n";
        }
        String decl = xhtml.substring(0, end + 2);
        if (isValidDeclaration(decl)) {
            return xhtml;
        }
        return CANONICAL + xhtml.substring(end + 2);
    }

    /**
     * 声明是否为解析器可接受的形态：{@code version} 合法引号串，{@code encoding}
     * 是合法的编码名（字母开头，字母数字及 ._-）。比 DOM 兜一圈便宜得多，且不会
     * 误伤规范声明。
     */
    private static boolean isValidDeclaration(String decl) {
        return decl.matches(
                "<\\?xml\\s+version=\"[^\"]*\"\\s+encoding=\"[A-Za-z][A-Za-z0-9._-]*\"\\s*\\?>");
    }
}
