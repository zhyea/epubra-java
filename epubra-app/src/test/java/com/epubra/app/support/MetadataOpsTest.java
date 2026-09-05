package com.epubra.app.support;

import com.epubra.epublib.domain.Metadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetadataOps} 单元测试：覆盖 snapshot / matches / apply / isDirty / isBlank
 * 五个入口的空参、往返与字段级差异。无需 JavaFX Toolkit。
 */
class MetadataOpsTest {

    @Test
    void snapshotReadsAllEditableFieldsFromMetadata() {
        Metadata m = sample();
        MetadataDraft draft = MetadataOps.snapshot(m);

        assertEquals("第一本书", draft.title());
        assertEquals("张三、李四", draft.authors());
        assertEquals("zh-CN", draft.language());
        assertEquals("某出版社", draft.publisher());
        assertEquals("这是一段简介。", draft.description());
    }

    @Test
    void snapshotOnNullMetadataReturnsEmptyDraft() {
        MetadataDraft draft = MetadataOps.snapshot(null);

        assertEquals(MetadataDraft.empty(), draft);
        assertTrue(MetadataOps.isBlank(draft));
    }

    @Test
    void snapshotOnBlankMetadataStillMatchesEmptyDraft() {
        Metadata m = new Metadata(); // 只有默认 language="zh-CN"
        MetadataDraft draft = MetadataOps.snapshot(m);

        assertEquals("", draft.title());
        assertEquals("", draft.authors());
        assertEquals("zh-CN", draft.language()); // 默认值原样导出
        assertEquals("", draft.publisher());
        assertEquals("", draft.description());
        assertTrue(MetadataOps.matches(m, draft));
    }

    @Test
    void matchesDetectsNoChangeWhenDraftEqualsSnapshot() {
        Metadata m = sample();
        MetadataDraft draft = MetadataOps.snapshot(m);

        assertTrue(MetadataOps.matches(m, draft));
        assertFalse(MetadataOps.isDirty(m, draft));
    }

    @Test
    void matchesDetectsSingleFieldChange() {
        Metadata m = sample();
        MetadataDraft dirty = new MetadataDraft(
                m.firstTitle(), m.creatorsInline(), m.language(),
                m.publisher(), "新简介");

        assertFalse(MetadataOps.matches(m, dirty));
        assertTrue(MetadataOps.isDirty(m, dirty));
    }

    @Test
    void snapshotNormalizesNullFieldsToEmptyString() {
        Metadata m = sample();
        m.setDescription(null);
        m.setPublisher(null);

        MetadataDraft draft = MetadataOps.snapshot(m);

        // snapshot 必须把 null 归一为 ""，否则 matches 双方会出现 null vs "" 的语义鸿沟
        assertEquals("", draft.description());
        assertEquals("", draft.publisher());
    }

    @Test
    void metadataDraftCompactConstructorNormalizesNullFields() {
        // compact constructor 在构造时就完成归一——调用方无需自己处理 null
        MetadataDraft draft = new MetadataDraft(null, null, null, null, null);

        assertEquals("", draft.title());
        assertEquals("", draft.authors());
        assertEquals("", draft.language());
        assertEquals("", draft.publisher());
        assertEquals("", draft.description());
        assertTrue(MetadataOps.isBlank(draft));
    }

    @Test
    void snapshotDoesNotPreserveDefaultLanguageAsNull() {
        // Metadata 默认 language="zh-CN"；snapshot 必须如实导出这个值，
        // 否则 matches(empty) 会因为 "zh-CN" vs "" 而恒为 false——这正是元数据面板
        // 用户首次进入时遇到的现象：草稿与书内一致却被误判为脏。
        Metadata m = new Metadata();

        MetadataDraft draft = MetadataOps.snapshot(m);

        assertEquals("zh-CN", draft.language());
    }

    @Test
    void matchesWithNullDraftTreatsAsEmpty() {
        Metadata m = sample();
        // 全部填空 → 等同 MetadataDraft.empty()
        // 这里构建一个内容为空的 draft 来模拟 null
        MetadataDraft empty = MetadataDraft.empty();

        assertFalse(MetadataOps.matches(m, empty));
        assertTrue(MetadataOps.isDirty(m, empty));

        // 真正传 null 也算 empty
        assertFalse(MetadataOps.matches(m, null));
    }

    @Test
    void applyOverwritesMetadataFieldsFromDraft() {
        Metadata m = sample();
        // 顿号分隔 → 拆成两条 creator，便于同时验证多值拆解路径
        MetadataDraft draft = new MetadataDraft(
                "新书名", "新作者A、新作者B", "en-US", "新出版社", "新简介");

        MetadataOps.apply(m, draft);

        assertEquals("新书名", m.firstTitle());
        assertEquals(2, m.creators().size());
        assertEquals("新作者A", m.creators().get(0));
        assertEquals("新作者B", m.creators().get(1));
        assertEquals("en-US", m.language());
        assertEquals("新出版社", m.publisher());
        assertEquals("新简介", m.description());
    }

    @Test
    void applyRoundTripPreservesAllFields() {
        Metadata m = sample();
        MetadataDraft draft = MetadataOps.snapshot(m);

        MetadataOps.apply(m, draft);
        MetadataDraft after = MetadataOps.snapshot(m);

        assertEquals(draft, after);
    }

    @Test
    void applyBlankDraftClearsMetadataFields() {
        // 注意：Metadata.setLanguage / setPublisher / setDescription 直接整字段覆盖，
        // 故空串会真实落库，且 language() 默认值"zh-CN"会被""覆盖（无回退）。
        Metadata m = sample();

        MetadataOps.apply(m, MetadataDraft.empty());

        assertEquals("", m.firstTitle());
        assertEquals("", m.creatorsInline());
        assertEquals("", m.language());
        assertEquals("", m.publisher());
        assertEquals("", m.description());
    }

    @Test
    void applyOnNullMetadataIsSilentNoOp() {
        // 防御 null：UI 边界场景里 metadata 临时不可用也不应 NPE
        MetadataOps.apply(null, MetadataDraft.empty());
        // 若不抛异常即视为通过
    }

    @Test
    void isBlankForFullyEmptyDrafts() {
        assertTrue(MetadataOps.isBlank(MetadataDraft.empty()));
        assertTrue(MetadataOps.isBlank(null));
    }

    @Test
    void isBlankReturnsFalseIfAnyFieldNonBlank() {
        assertFalse(MetadataOps.isBlank(new MetadataDraft("x", "", "", "", "")));
        assertFalse(MetadataOps.isBlank(new MetadataDraft("", "x", "", "", "")));
        assertFalse(MetadataOps.isBlank(new MetadataDraft("", "", "en", "", "")));
        assertFalse(MetadataOps.isBlank(new MetadataDraft("", "", "", "pub", "")));
        assertFalse(MetadataOps.isBlank(new MetadataDraft("", "", "", "", "desc")));
    }

    private static Metadata sample() {
        Metadata m = new Metadata();
        m.setFirstTitle("第一本书");
        m.setCreatorsInline("张三、李四");
        m.setLanguage("zh-CN");
        m.setPublisher("某出版社");
        m.setDescription("这是一段简介。");
        return m;
    }
}
