package org.chobit.epubra.lib.util;

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

    /** 把容器内路径转换为相对 baseDir 的路径，非 baseDir 前缀时原样返回。 */
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
