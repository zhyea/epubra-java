package org.chobit.epubra.app.components;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.ChapterTemplates;
import org.chobit.epubra.lib.domain.Resource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把纯文本按常见中文章节标题切分为 EPUB 正文章节。
 *
 * <p>识别规则参考常见网文导入格式：支持「第 1 章」「第一卷」「第十回 xxx」，
 * 以及「序、楔子、前言、后记」等短标题。连续出现多个标题时，最后一个标题作为
 * 后续正文的章节名。
 */
public final class TextChapterImporter {

    private static final Pattern CHAPTER_TITLE = Pattern.compile(
            "^第?[\\s]{0,9}[\\d〇零一二三四五六七八九十百千万上中下０１２３４５６７８９　\\s]{1,6}"
                    + "[\\s]{0,9}[、，．.]?[章回节卷部篇讲集分]{0,2}"
                    + "([\\s]{1,9}.{0,32})?$");

    private static final Set<String> SHORT_TITLES = new HashSet<>(Arrays.asList(
            "楔子", "引子", "引言", "前言", "序章", "序言", "序曲",
            "尾声", "终章", "后记", "序", "序幕", "跋", "附", "附言"));

    private TextChapterImporter() {
    }

    /**
     * 导入 TXT 内容。
     *
     * @param title 书籍标题，也是未识别到章节标题时的默认章节名
     * @param text  TXT 文本内容
     */
    public static Book importBook(String title, String text) {
        String bookTitle = normalizeTitle(title);
        List<ImportedChapter> chapters = split(bookTitle, text);

        Book book = BookFactory.createEmpty(bookTitle);
        Resource first = book.spineResources().get(0);
        ImportedChapter firstChapter = chapters.get(0);
        first.setString(toXhtml(firstChapter.title(), firstChapter.body()));
        book.toc().roots().get(0).setTitle(firstChapter.title());

        for (int i = 1; i < chapters.size(); i++) {
            ImportedChapter chapter = chapters.get(i);
            book.addChapter(chapter.title(), toXhtml(chapter.title(), chapter.body()));
        }
        return book;
    }

    static List<ImportedChapter> split(String title, String text) {
        String defaultTitle = normalizeTitle(title);
        String normalized = text == null
                ? ""
                : text.replace("\r\n", "\n").replace('\r', '\n');

        List<ImportedChapter> chapters = new java.util.ArrayList<>();
        String chapterTitle = defaultTitle;
        StringBuilder body = new StringBuilder();
        for (String rawLine : normalized.split("\\n", -1)) {
            String line = normalizeLine(rawLine);
            if (isChapterTitle(line)) {
                if (body.length() > 0) {
                    chapters.add(new ImportedChapter(chapterTitle, body.toString()));
                    body.setLength(0);
                }
                chapterTitle = line;
                continue;
            }
            line = line.replace(" ", "");
            if (!line.isBlank()) {
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        if (body.length() > 0 || chapters.isEmpty()) {
            chapters.add(new ImportedChapter(chapterTitle, body.toString()));
        }
        return List.copyOf(chapters);
    }

    static boolean isChapterTitle(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return SHORT_TITLES.contains(line) || CHAPTER_TITLE.matcher(line).matches();
    }

    private static String normalizeLine(String line) {
        return line == null ? "" : line.replace('　', ' ').trim();
    }

    private static String normalizeTitle(String title) {
        return title == null || title.isBlank() ? "导入文本" : title.trim();
    }

    private static String toXhtml(String title, String body) {
        StringBuilder paragraphs = new StringBuilder();
        for (String line : body.split("\\n", -1)) {
            if (!line.isBlank()) {
                paragraphs.append("  <p>")
                        .append(ChapterTemplates.escape(line))
                        .append("</p>\n");
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n"
                + "<head><title>" + ChapterTemplates.escape(title) + "</title></head>\n"
                + "<body>\n<h1>" + ChapterTemplates.escape(title) + "</h1>\n"
                + paragraphs
                + "</body>\n</html>\n";
    }

    record ImportedChapter(String title, String body) {
    }
}
