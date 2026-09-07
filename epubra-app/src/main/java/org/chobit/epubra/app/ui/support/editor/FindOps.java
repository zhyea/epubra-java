package org.chobit.epubra.app.ui.support.editor;

/**
 * 章节内查找的索引推进逻辑：含回卷（从结尾回到开头，或反向）。
 *
 * <p>UI 路径只在 MainController 里调度 {@link TextSearch} 与 JavaFX TextArea；这里把
 * 「向前找：从 from 起，命中返回索引；未命中回到 0 再找；仍未命中返回 -1」这类判定抽出来，
 * 便于单元测试覆盖正反向与回卷行为。
 */
public final class FindOps {

    private FindOps() {
    }

    /** 从 {@code from} 起在 {@code text} 里找 {@code keyword}；未命中则回卷到开头再找；都没有则返回 -1。 */
    public static int indexOfForward(String text, String keyword, int from, boolean caseSensitive) {
        int hit = TextSearch.indexOf(text, keyword, from, caseSensitive);
        if (hit >= 0) {
            return hit;
        }
        return TextSearch.indexOf(text, keyword, 0, caseSensitive);
    }

    /** 从 {@code from} 起在 {@code text} 里反向找 {@code keyword}；未命中则回卷到结尾再找；都没有则返回 -1。 */
    public static int indexOfBackward(String text, String keyword, int from, boolean caseSensitive) {
        int hit = TextSearch.lastIndexOf(text, keyword, from, caseSensitive);
        if (hit >= 0) {
            return hit;
        }
        return TextSearch.lastIndexOf(text, keyword, text.length() - 1, caseSensitive);
    }

    /**
     * 在查找操作的常规结果之上，派生要给用户的提示语：
     * <ul>
     *   <li>命中：返回空串（不带额外说明）。</li>
     *   <li>未命中：返回 {@code "未找到"}。</li>
     *   <li>回卷命中：返回 {@code "已回到开头"} 或 {@code "已回到结尾"}。</li>
     * </ul>
     *
     * @param primaryHit   第一次查找的命中索引（-1 表示未命中）
     * @param wrapAroundHit 回卷再查的命中索引（-1 表示仍未命中）
     * @param forward      是否为正向查找
     */
    public static String wrapStatus(int primaryHit, int wrapAroundHit, boolean forward) {
        if (primaryHit >= 0) {
            return "";
        }
        if (wrapAroundHit >= 0) {
            return forward ? "已回到开头" : "已回到结尾";
        }
        return "未找到";
    }
}
