package com.epubra.epublib.validation;

import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.EpubVersion;
import com.epubra.epublib.domain.Metadata;

import java.util.List;

import static com.epubra.epublib.validation.StructureSupport.LANGUAGE;
import static com.epubra.epublib.validation.StructureSupport.MODIFIED_PROPERTY;
import static com.epubra.epublib.validation.StructureSupport.nullSafe;

/**
 * F 组：元数据完整性（标题、唯一标识符、语言、modified 时间戳）。
 */
final class MetadataRules {

    private MetadataRules() {
    }

    static void check(Book book, List<ValidationIssue> issues) {
        Metadata metadata = book.metadata();
        if (metadata == null) {
            return;
        }
        if (metadata.firstTitle() == null || metadata.firstTitle().isBlank()) {
            issues.add(new ValidationIssue(IssueKind.METADATA_TITLE_MISSING, "书籍缺少标题"));
        }
        Metadata.Identifier identifier = metadata.primaryIdentifier();
        if (identifier == null || identifier.value() == null || identifier.value().isBlank()) {
            issues.add(new ValidationIssue(IssueKind.METADATA_IDENTIFIER_MISSING,
                    "书籍缺少唯一标识符（dc:identifier）"));
        }
        String language = metadata.language();
        if (language == null || language.isBlank()) {
            issues.add(new ValidationIssue(IssueKind.METADATA_LANGUAGE_MISSING, "书籍缺少语言（dc:language）"));
        } else if (!LANGUAGE.matcher(language.trim()).matches()) {
            issues.add(new ValidationIssue(IssueKind.METADATA_LANGUAGE_MISSING,
                    "语言代码 '" + language + "' 不符合 BCP 47 格式（如 zh-CN、en）",
                    null,
                    "language=" + language));
        }
        if (book.version() == EpubVersion.EPUB_3) {
            String modified = metadata.property(MODIFIED_PROPERTY);
            if (modified == null || modified.isBlank()) {
                issues.add(new ValidationIssue(IssueKind.METADATA_MODIFIED_MISSING,
                        "EPUB 3 要求元数据中带有 " + MODIFIED_PROPERTY + " 时间戳",
                        null,
                        "property=" + MODIFIED_PROPERTY));
            }
        }
    }
}
