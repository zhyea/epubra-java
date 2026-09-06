package org.chobit.epubra.lib.domain;

import java.util.UUID;

/**
 * 新建书籍的模板工厂。
 */
public final class BookFactory {

    /** 创建一本带单个空章节的新书。 */
    public static Book createEmpty() {
        return createEmpty("新书籍");
    }

    public static Book createEmpty(String title) {
        Book book = new Book();
        book.metadata().setFirstTitle(title == null || title.isBlank() ? "新书籍" : title.trim());
        book.metadata().addIdentifier(new Metadata.Identifier("pub-id", "uuid", UUID.randomUUID().toString(), true));
        book.metadata().setLanguage("zh-CN");
        book.addChapter("第一章", null);
        return book;
    }

    private BookFactory() {
    }
}
