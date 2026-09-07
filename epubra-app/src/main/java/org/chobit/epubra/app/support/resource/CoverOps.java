package org.chobit.epubra.app.support.resource;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;

/**
 * 封面相关纯逻辑：状态判定 + 资源可否作封面 + 设置 / 清除。
 *
 * <p>把 {@link Book#setCover(Resource)} / {@link Book#coverResource()} / {@link Book#coverResourceId()}
 * 三件封装成 UI 能直接展示的「三态」——与校验规则 {@code D18}（封面 id 悬空）形成一一对应，
 * 让封面卡在打开书后立刻反映当前是否健康，不必等到用户跑校验。
 *
 * <p>命名沿用项目里既有纯逻辑类的风格（如 {@link MetadataOps} / {@link ResourceOps}），
 * 工具方法全部 {@code static}，不持有任何状态，方便单元测试直接覆盖。
 */
public final class CoverOps {

    private CoverOps() {
    }

    /**
     * 封面在 UI 上的三态。
     *
     * <ul>
     *   <li>{@link #EMPTY}：{@code coverResourceId} 为空（{@code null} / 全空白），UI 显示「未设置」占位。</li>
     *   <li>{@link #SET}：{@code coverResource()} 能命中资源，UI 显示缩略图 + 文件名等信息。</li>
     *   <li>{@link #DANGLING}：{@code coverResourceId} 非空，但 manifest 中已无对应资源
     *       （等价于校验规则 D18），UI 显红提示「封面引用失效」，允许「清除引用」或「重新选择」。</li>
     * </ul>
     */
    public enum CoverState {
        EMPTY, SET, DANGLING
    }

    /**
     * 判定一本书当前的封面状态。{@code null} 书籍按 {@link CoverState#EMPTY} 处理，避免 NPE。
     */
    public static CoverState describe(Book book) {
        if (book == null) {
            return CoverState.EMPTY;
        }
        String id = book.coverResourceId();
        if (id == null || id.isBlank()) {
            // 顺带看一眼 cover-image 属性：哪怕 id 没设，资源上挂 cover-image 仍是有效封面。
            return hasAnyCoverImageProperty(book) ? CoverState.SET : CoverState.EMPTY;
        }
        return book.coverResource().isPresent() ? CoverState.SET : CoverState.DANGLING;
    }

    /**
     * 资源可否作为封面。封面在 EPUB 里是图片，所以只有 {@code image/*} 媒体类型才算合法。
     *
     * <p>保留 {@link Resource#properties()} 的「图片」字面判定兜底——极端情况下用户可能
     * 通过 {@link Book#addResource(java.nio.file.Path)} 导入了一张扩展名错的位图，
     * 只要 mediaType 是 image/ 就行。
     */
    public static boolean pick(Book book, Resource resource) {
        return resource != null && book != null && resource.mediaType() != null
                && resource.mediaType().startsWith("image/");
    }

    /**
     * 把 {@code resource} 设为封面并写好 OPF 双写（{@code <meta name="cover">} +
     * manifest item properties=cover-image）。{@code null} 资源视为清除。
     *
     * <p>方法本身只调 {@link Book#setCover(Resource)}，因此撤销语义与「打开书 → 加载 OPF →
     * 改任何字段」完全一致——调用方仍需自己先拍快照（项目惯例是 {@code beginChange()}）。
     */
    public static void set(Book book, Resource resource) {
        if (book == null) {
            return;
        }
        book.setCover(resource);
    }

    /**
     * 清除封面：等价 {@code book.setCover(null)}。这里包装一层纯属为了与 {@link #set}
     * 形成对称——UI 层不需要先判断「是不是已经设过」再决定调 set 还是 clear，直接 clear 即可
     * （清除已清的对象是幂等的）。
     */
    public static void clear(Book book) {
        set(book, null);
    }

    private static boolean hasAnyCoverImageProperty(Book book) {
        for (Resource r : book.resources().all()) {
            String p = r.properties();
            if (p != null && p.contains("cover-image")) {
                return true;
            }
        }
        return false;
    }

    /** Image 类型常量白名单：PNG / JPEG / GIF / WebP / SVG。封面只接受这五种。 */
    public static boolean isCoverImageType(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        return MediaTypes.PNG.equals(mediaType)
                || MediaTypes.JPEG.equals(mediaType)
                || MediaTypes.GIF.equals(mediaType)
                || MediaTypes.WEBP.equals(mediaType)
                || MediaTypes.SVG.equals(mediaType);
    }
}
