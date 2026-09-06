package org.chobit.epubra.lib.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OPF 中的 {code dc-metadata} 与 EPUB 3 {@code meta} 扩展属性。
 */
public class Metadata {

    private final List<String> titles = new ArrayList<>();
    private final List<String> creators = new ArrayList<>();
    private final List<Identifier> identifiers = new ArrayList<>();
    private final List<String> subjects = new ArrayList<>();

    private String language = "zh-CN";
    private String publisher;
    private String description;
    private String date;
    private String rights;

    /** EPUB 3 的 {@code <meta property="...">} 键值对。 */
    private final Map<String, String> properties = new LinkedHashMap<>();

    public List<String> titles() {
        return titles;
    }

    public void addTitle(String title) {
        if (title != null && !title.isBlank()) {
            titles.add(title.trim());
        }
    }

    public String firstTitle() {
        return titles.isEmpty() ? "" : titles.get(0);
    }

    public void setFirstTitle(String title) {
        if (titles.isEmpty()) {
            addTitle(title);
        } else {
            titles.set(0, title);
        }
    }

    public List<String> creators() {
        return creators;
    }

    public void addCreator(String creator) {
        if (creator != null && !creator.isBlank()) {
            creators.add(creator.trim());
        }
    }

    /** 作者姓名，多个以顿号连接（用于界面展示）。 */
    public String creatorsInline() {
        return String.join("、", creators);
    }

    public void setCreatorsInline(String inline) {
        creators.clear();
        if (inline == null || inline.isBlank()) {
            return;
        }
        for (String part : inline.split("[、,，;；]")) {
            addCreator(part);
        }
    }

    public List<Identifier> identifiers() {
        return identifiers;
    }

    public void addIdentifier(Identifier identifier) {
        if (identifier != null) {
            identifiers.add(identifier);
        }
    }

    /** 主键标识符（OPF unique-identifier 指向的那个），没有时取第一个。 */
    public Identifier primaryIdentifier() {
        return identifiers.stream()
                .filter(Identifier::primary)
                .findFirst()
                .orElseGet(() -> identifiers.isEmpty() ? null : identifiers.get(0));
    }

    public List<String> subjects() {
        return subjects;
    }

    public void addSubject(String subject) {
        if (subject != null && !subject.isBlank()) {
            subjects.add(subject.trim());
        }
    }

    public String language() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String publisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public String description() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String date() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String rights() {
        return rights;
    }

    public void setRights(String rights) {
        this.rights = rights;
    }

    public Map<String, String> properties() {
        return properties;
    }

    public String property(String name) {
        return properties.get(name);
    }

    public void setProperty(String name, String value) {
        properties.put(name, value);
    }

    /** dc:identifier 及其在 OPF 上的 id / 是否为主键。 */
    public record Identifier(String id, String scheme, String value, boolean primary) {

        public Identifier(String scheme, String value) {
            this(null, scheme, value, false);
        }

        public Identifier(String id, String scheme, String value) {
            this(id, scheme, value, false);
        }

        /** 形如 {@code uuid:xxx} 的值拆分为 scheme 与 value。 */
        public static Identifier parse(String id, String raw, boolean primary) {
            if (raw == null) {
                return null;
            }
            int colon = raw.indexOf(':');
            if (colon > 0 && raw.indexOf(' ', colon) < 0) {
                return new Identifier(id, raw.substring(0, colon), raw.substring(colon + 1), primary);
            }
            return new Identifier(id, null, raw, primary);
        }

        /** 写回 OPF 时使用的字面值，无 scheme 时只输出 value。 */
        public String raw() {
            return scheme == null || scheme.isBlank() ? value : scheme + ":" + value;
        }
    }
}
