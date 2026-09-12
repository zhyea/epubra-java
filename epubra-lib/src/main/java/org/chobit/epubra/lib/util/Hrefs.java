package org.chobit.epubra.lib.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器内路径的解析与规范化，路径统一使用 {@code /} 分隔。
 */
public final class Hrefs {

    private Hrefs() {
    }

    /** 父目录，含结尾的 {@code /}；位于根目录时返回空串。 */
    public static String parentDirectory(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? "" : normalized.substring(0, slash + 1);
    }

    public static String fileName(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    public static String extension(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /** 把 baseDir 与相对 href 拼接并消解 {@code .} / {@code ..}，保留片断标识符。 */
    public static String resolve(String baseDir, String href) {
        if (href == null) {
            return "";
        }
        String target = href.replace('\\', '/').trim();
        String fragment = "";
        int hash = target.indexOf('#');
        if (hash >= 0) {
            fragment = target.substring(hash);
            target = target.substring(0, hash);
        }
        if (target.startsWith("/")) {
            return normalize(target.substring(1)) + fragment;
        }
        if (target.isEmpty()) {
            return normalize(baseDir == null ? "" : baseDir) + fragment;
        }
        String base = baseDir == null ? "" : baseDir.replace('\\', '/');
        return normalize(base + target) + fragment;
    }

    private static String normalize(String path) {
        String[] parts = path.split("/");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                int slash = out.lastIndexOf("/");
                if (slash >= 0) {
                    out.delete(slash, out.length());
                }
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }

    /**
     * 把容器内路径相对 {@code baseDir} 表示为<b>可回溯</b>的相对路径。
     *
     * <p>与 {@link #relativize(String, String)} 的区别是这里做真正的分段比对：当
     * {@code path} 不在 {@code baseDir} 之下时，会产出 {@code ../} 前缀，而不是原样返回
     * 一条包内绝对路径。
     *
     * <pre>
     *   relativePath("OEBPS/",        "OEBPS/images/a.png")  → "images/a.png"
     *   relativePath("OEBPS/text/",   "OEBPS/images/a.png")  → "../images/a.png"
     *   relativePath("OEBPS/a/b/",    "OEBPS/images/a.png")  → "../../images/a.png"
     *   relativePath("",              "OEBPS/images/a.png")  → "OEBPS/images/a.png"
     * </pre>
     *
     * <p>最后一个路径段永远视作「文件名」参与输出、不参与公共前缀比对，因此不会出现
     * 把文件名本身消解掉的退化结果。{@code baseDir} 位于根（空串）时等价于直接返回
     * {@code path}。
     *
     * @param baseDir 基准目录（可带可不带结尾 {@code /}），空串表示容器根
     * @param path    目标路径，容器内绝对；以 {@code /} 开头时按容器根处理
     * @return 相对路径；{@code path} 为空时返回空串
     */
    public static String relativePath(String baseDir, String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String target = path.replace('\\', '/');
        if (target.startsWith("/")) {
            target = target.substring(1);
        }
        List<String> base = segments(baseDir);
        List<String> full = segments(target);
        if (full.isEmpty()) {
            return "";
        }
        int common = 0;
        // 末段是文件名，不参与比对：否则 OEBPS/a.png 相对 OEBPS/ 会算出 "../a.png"
        while (common < base.size() && common < full.size() - 1
                && base.get(common).equals(full.get(common))) {
            common++;
        }
        StringBuilder out = new StringBuilder();
        for (int i = common; i < base.size(); i++) {
            out.append("../");
        }
        for (int i = common; i < full.size(); i++) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '/') {
                out.append('/');
            }
            out.append(full.get(i));
        }
        return out.toString();
    }

    /** 按 {@code /} 切分并消解 {@code .} / {@code ..}；空段与首尾斜杠被丢弃。 */
    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(part);
        }
        return out;
    }

    /**
     * 剥掉 {@code baseDir} 前缀，得到容器内路径相对它的写法。
     *
     * <p><b>只做前缀剥离，不消解 {@code ..}</b>：{@code path} 不在 {@code baseDir} 之下时
     * 原样返回。这个语义正是清单 / NCX 生成需要的（资源都在 OPF 目录之下，前缀必然命中），
     * 但<b>不能</b>用来给正文里的引用算相对路径——那种场景必须用
     * {@link #relativePath(String, String)}，否则跨目录时会写出包内绝对路径。
     */
    public static String relativize(String baseDir, String path) {
        if (baseDir == null || baseDir.isEmpty()) {
            return path;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith(baseDir)) {
            return normalized.substring(baseDir.length());
        }
        return normalized;
    }
}
