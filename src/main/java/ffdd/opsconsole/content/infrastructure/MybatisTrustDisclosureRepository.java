package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.FinancialFieldView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustSectionFieldView;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.mapper.DisclosureChapterMapper;
import ffdd.opsconsole.content.mapper.DisclosureDraftMapper;
import ffdd.opsconsole.content.mapper.DisclosureGateActionMapper;
import ffdd.opsconsole.content.mapper.DisclosureJurisdictionMapper;
import ffdd.opsconsole.content.mapper.TrustSectionFieldMapper;
import ffdd.opsconsole.content.mapper.TrustSectionMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisTrustDisclosureRepository implements TrustDisclosureRepository {
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final String FINANCIALS = "financials";
    private static final String NEX_V2 = "nexv2";

    private final TrustSectionMapper trustSectionMapper;
    private final TrustSectionFieldMapper trustSectionFieldMapper;
    private final DisclosureJurisdictionMapper disclosureJurisdictionMapper;
    private final DisclosureChapterMapper disclosureChapterMapper;
    private final DisclosureGateActionMapper disclosureGateActionMapper;
    private final DisclosureDraftMapper disclosureDraftMapper;

    private static final List<TrustSectionSeed> TRUST_SECTION_SEEDS = List.of();
    private static final List<FieldSeed> FIELD_SEEDS = List.of();
    private static final List<JurisdictionSeed> JURISDICTION_SEEDS = List.of();
    private static final List<ChapterSeed> CHAPTER_SEEDS = List.of();
    private static final List<GateSeed> GATE_SEEDS = List.of();

    @Override
    public void ensureSeedData(LocalDateTime now) {
        // Business rows must come from MySQL writes, not read-time demo seeds.
    }

    @Override
    public List<TrustSectionView> listTrustSections() {
        return trustSectionMapper.selectList(new LambdaQueryWrapper<TrustSectionEntity>()
                        .eq(TrustSectionEntity::getIsDeleted, 0)
                        .orderByAsc(TrustSectionEntity::getSortOrder)
                        .orderByAsc(TrustSectionEntity::getId))
                .stream()
                .map(this::toTrustSection)
                .toList();
    }

    @Override
    public Optional<TrustSectionView> findTrustSection(String sectionKey) {
        return Optional.ofNullable(findTrustSectionEntity(sectionKey)).map(this::toTrustSection);
    }

    @Override
    public List<TrustSectionFieldView> listSectionFields() {
        return trustSectionFieldMapper.selectList(new LambdaQueryWrapper<TrustSectionFieldEntity>()
                        .eq(TrustSectionFieldEntity::getIsDeleted, 0)
                        .ne(TrustSectionFieldEntity::getSectionKey, FINANCIALS)
                        .orderByAsc(TrustSectionFieldEntity::getSectionKey)
                        .orderByAsc(TrustSectionFieldEntity::getSortOrder)
                        .orderByAsc(TrustSectionFieldEntity::getId))
                .stream()
                .map(entity -> new TrustSectionFieldView(
                        entity.getSectionKey(),
                        entity.getFieldKey(),
                        entity.getFieldValue()))
                .toList();
    }

    @Override
    public List<FinancialFieldView> listFinancialFields() {
        return trustSectionFieldMapper.selectList(new LambdaQueryWrapper<TrustSectionFieldEntity>()
                        .eq(TrustSectionFieldEntity::getIsDeleted, 0)
                        .eq(TrustSectionFieldEntity::getSectionKey, FINANCIALS)
                        .orderByAsc(TrustSectionFieldEntity::getSortOrder)
                        .orderByAsc(TrustSectionFieldEntity::getId))
                .stream()
                .map(entity -> new FinancialFieldView(
                        entity.getFieldKey(),
                        entity.getFieldValue(),
                        entity.getFieldDelta()))
                .toList();
    }

    @Override
    public List<DisclosureJurisdictionView> listJurisdictions() {
        return disclosureJurisdictionMapper.selectList(new LambdaQueryWrapper<DisclosureJurisdictionEntity>()
                        .eq(DisclosureJurisdictionEntity::getIsDeleted, 0)
                        .orderByAsc(DisclosureJurisdictionEntity::getId))
                .stream()
                .map(this::toJurisdiction)
                .toList();
    }

    @Override
    public Optional<DisclosureJurisdictionView> findJurisdiction(String jurisdiction) {
        return Optional.ofNullable(findJurisdictionEntity(jurisdiction)).map(this::toJurisdiction);
    }

    @Override
    public List<DisclosureChapterView> listChapters(String jurisdiction, String version) {
        return disclosureChapterMapper.selectList(new LambdaQueryWrapper<DisclosureChapterEntity>()
                        .eq(DisclosureChapterEntity::getIsDeleted, 0)
                        .eq(StringUtils.hasText(jurisdiction), DisclosureChapterEntity::getJurisdictionCode, normalizeUpper(jurisdiction))
                        .eq(StringUtils.hasText(version), DisclosureChapterEntity::getVersionLabel, normalize(version))
                        .orderByAsc(DisclosureChapterEntity::getSortOrder)
                        .orderByAsc(DisclosureChapterEntity::getId))
                .stream()
                .map(this::toChapter)
                .toList();
    }

    @Override
    public List<DisclosureGateActionView> listGateActions() {
        return disclosureGateActionMapper.selectList(new LambdaQueryWrapper<DisclosureGateActionEntity>()
                        .eq(DisclosureGateActionEntity::getIsDeleted, 0)
                        .orderByAsc(DisclosureGateActionEntity::getSortOrder)
                        .orderByAsc(DisclosureGateActionEntity::getId))
                .stream()
                .map(this::toGateAction)
                .toList();
    }

    @Override
    public Optional<DisclosureDraftView> findLatestDraft() {
        return Optional.ofNullable(disclosureDraftMapper.selectOne(new LambdaQueryWrapper<DisclosureDraftEntity>()
                        .eq(DisclosureDraftEntity::getIsDeleted, 0)
                        .orderByDesc(DisclosureDraftEntity::getId)
                        .last("LIMIT 1")))
                .map(this::toDraft);
    }

    @Override
    public Optional<DisclosureDraftView> findDraft(String jurisdiction) {
        return Optional.ofNullable(disclosureDraftMapper.selectOne(new LambdaQueryWrapper<DisclosureDraftEntity>()
                        .eq(DisclosureDraftEntity::getIsDeleted, 0)
                        .eq(DisclosureDraftEntity::getJurisdictionCode, normalizeUpper(jurisdiction))
                        .orderByDesc(DisclosureDraftEntity::getId)
                        .last("LIMIT 1")))
                .map(this::toDraft);
    }

    @Override
    public void updateTrustSection(String sectionKey, String version, String status, String operator, LocalDateTime now) {
        TrustSectionEntity entity = findTrustSectionEntity(sectionKey);
        if (entity == null) {
            return;
        }
        entity.setVersionLabel(normalize(version));
        entity.setStatus(normalizeUpper(status));
        entity.setLastChange(DAY_LABEL.format(now));
        entity.setLastOperator(operator(operator));
        entity.setUpdatedAt(now);
        trustSectionMapper.updateById(entity);
    }

    @Override
    public void saveDisclosureDraft(DisclosureDraftRequest request, String status, LocalDateTime now) {
        DisclosureDraftEntity draft = findDraftEntity(request.jurisdiction(), request.version());
        if (draft == null) {
            draft = new DisclosureDraftEntity();
            draft.setJurisdictionCode(normalizeUpper(request.jurisdiction()));
            draft.setVersionLabel(normalize(request.version()));
            draft.setCreatedAt(now);
            draft.setIsDeleted(0);
        }
        draft.setLanguageScope(normalize(request.languageScope()));
        draft.setEffectiveDate(normalize(request.effectiveDate()));
        draft.setRequiresReack(Boolean.TRUE.equals(request.requiresReack()));
        draft.setZhBody(normalize(request.zh()));
        draft.setEnBody(normalize(request.en()));
        draft.setStatus(normalizeUpper(status));
        draft.setLastOperator(operator(request.operator()));
        draft.setUpdatedAt(now);
        if (draft.getId() == null) {
            disclosureDraftMapper.insert(draft);
        } else {
            disclosureDraftMapper.updateById(draft);
        }
        ensureJurisdictionForDraft(request, status, now);
    }

    @Override
    public void publishDisclosure(String jurisdiction, DisclosureDraftRequest request, LocalDateTime now) {
        DisclosureJurisdictionEntity current = findJurisdictionEntity(jurisdiction);
        String previousVersion = current == null ? null : current.getVersionLabel();
        saveDisclosureDraft(request, "PUBLISHED", now);
        current = findJurisdictionEntity(jurisdiction);
        if (current != null) {
            current.setVersionLabel(normalize(request.version()));
            current.setStatus("PUBLISHED");
            current.setPublishedAtLabel(DAY_LABEL.format(now));
            current.setAckProgressPct(Boolean.TRUE.equals(request.requiresReack()) ? BigDecimal.ZERO : BigDecimal.valueOf(100));
            current.setLastOperator(operator(request.operator()));
            current.setUpdatedAt(now);
            disclosureJurisdictionMapper.updateById(current);
        }
        ensureChapters(normalizeUpper(jurisdiction), previousVersion, request, now);
    }

    private void ensureJurisdictionForDraft(DisclosureDraftRequest request, String status, LocalDateTime now) {
        DisclosureJurisdictionEntity current = findJurisdictionEntity(request.jurisdiction());
        if (current != null) {
            return;
        }
        DisclosureJurisdictionEntity entity = new DisclosureJurisdictionEntity();
        entity.setJurisdictionCode(normalizeUpper(request.jurisdiction()));
        entity.setJurisdictionName(normalizeUpper(request.jurisdiction()));
        entity.setVersionLabel(normalize(request.version()));
        entity.setStatus(normalizeUpper(status));
        entity.setPublishedAtLabel("PUBLISHED".equalsIgnoreCase(status) ? DAY_LABEL.format(now) : "");
        entity.setAffectedCount(0L);
        entity.setAckProgressPct(Boolean.TRUE.equals(request.requiresReack()) ? BigDecimal.ZERO : BigDecimal.valueOf(100));
        entity.setBlockedCount(0L);
        entity.setLastOperator(operator(request.operator()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        disclosureJurisdictionMapper.insert(entity);
    }

    @Override
    public void updateGateScope(Set<String> activeKeys, String operator, LocalDateTime now) {
        disclosureGateActionMapper.selectList(new LambdaQueryWrapper<DisclosureGateActionEntity>()
                        .eq(DisclosureGateActionEntity::getIsDeleted, 0))
                .forEach(entity -> {
                    boolean sunset = NEX_V2.equalsIgnoreCase(entity.getActionKey());
                    entity.setActive(!sunset && activeKeys.contains(entity.getActionKey()));
                    entity.setLastOperator(operator(operator));
                    entity.setUpdatedAt(now);
                    disclosureGateActionMapper.updateById(entity);
                });
    }

    private void ensureChapters(String jurisdiction, String previousVersion, DisclosureDraftRequest request, LocalDateTime now) {
        String version = normalize(request.version());
        if (!listChapters(jurisdiction, version).isEmpty()) {
            return;
        }
        List<DisclosureChapterEntity> source = disclosureChapterMapper.selectList(new LambdaQueryWrapper<DisclosureChapterEntity>()
                .eq(DisclosureChapterEntity::getIsDeleted, 0)
                .eq(DisclosureChapterEntity::getJurisdictionCode, jurisdiction)
                .eq(StringUtils.hasText(previousVersion), DisclosureChapterEntity::getVersionLabel, previousVersion)
                .orderByAsc(DisclosureChapterEntity::getSortOrder)
                .orderByAsc(DisclosureChapterEntity::getId));
        for (DisclosureChapterEntity current : source) {
            DisclosureChapterEntity chapter = new DisclosureChapterEntity();
            chapter.setJurisdictionCode(jurisdiction);
            chapter.setVersionLabel(version);
            chapter.setChapterNo(current.getChapterNo());
            chapter.setZhTitle(current.getZhTitle());
            chapter.setEnTitle(current.getEnTitle());
            chapter.setZhBody(request.zh().trim());
            chapter.setEnBody(request.en().trim());
            chapter.setSortOrder(current.getSortOrder());
            chapter.setLastOperator(operator(request.operator()));
            chapter.setCreatedAt(now);
            chapter.setUpdatedAt(now);
            chapter.setIsDeleted(0);
            disclosureChapterMapper.insert(chapter);
        }
    }

    private TrustSectionEntity findTrustSectionEntity(String sectionKey) {
        return trustSectionMapper.selectOne(new LambdaQueryWrapper<TrustSectionEntity>()
                .eq(TrustSectionEntity::getSectionKey, normalize(sectionKey))
                .eq(TrustSectionEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private DisclosureJurisdictionEntity findJurisdictionEntity(String jurisdiction) {
        return disclosureJurisdictionMapper.selectOne(new LambdaQueryWrapper<DisclosureJurisdictionEntity>()
                .eq(DisclosureJurisdictionEntity::getJurisdictionCode, normalizeUpper(jurisdiction))
                .eq(DisclosureJurisdictionEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private DisclosureDraftEntity findDraftEntity(String jurisdiction, String version) {
        return disclosureDraftMapper.selectOne(new LambdaQueryWrapper<DisclosureDraftEntity>()
                .eq(DisclosureDraftEntity::getJurisdictionCode, normalizeUpper(jurisdiction))
                .eq(DisclosureDraftEntity::getVersionLabel, normalize(version))
                .eq(DisclosureDraftEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private void ensureTrustSection(TrustSectionSeed seed, LocalDateTime now) {
        if (findTrustSectionEntity(seed.key()) != null) {
            return;
        }
        TrustSectionEntity entity = new TrustSectionEntity();
        entity.setSectionKey(seed.key());
        entity.setDescription(seed.description());
        entity.setStructText(seed.structText());
        entity.setVersionLabel(seed.version());
        entity.setStatus(seed.status());
        entity.setRoleGate(seed.roleGate());
        entity.setHighSensitivity(seed.highSensitivity());
        entity.setLastChange(seed.lastChange());
        entity.setSortOrder(seed.sortOrder());
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        trustSectionMapper.insert(entity);
    }

    private void ensureField(FieldSeed seed, LocalDateTime now) {
        TrustSectionFieldEntity existing = trustSectionFieldMapper.selectOne(new LambdaQueryWrapper<TrustSectionFieldEntity>()
                .eq(TrustSectionFieldEntity::getSectionKey, seed.sectionKey())
                .eq(TrustSectionFieldEntity::getFieldKey, seed.key())
                .eq(TrustSectionFieldEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        TrustSectionFieldEntity entity = new TrustSectionFieldEntity();
        entity.setSectionKey(seed.sectionKey());
        entity.setFieldKey(seed.key());
        entity.setFieldValue(seed.value());
        entity.setFieldDelta(seed.delta());
        entity.setSortOrder(seed.sortOrder());
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        trustSectionFieldMapper.insert(entity);
    }

    private void ensureJurisdiction(JurisdictionSeed seed, LocalDateTime now) {
        if (findJurisdictionEntity(seed.code()) != null) {
            return;
        }
        DisclosureJurisdictionEntity entity = new DisclosureJurisdictionEntity();
        entity.setJurisdictionCode(seed.code());
        entity.setJurisdictionName(seed.name());
        entity.setVersionLabel(seed.version());
        entity.setStatus(seed.status());
        entity.setPublishedAtLabel(seed.publishedAt());
        entity.setAffectedCount(seed.affected());
        entity.setAckProgressPct(new BigDecimal(seed.ackPct()));
        entity.setBlockedCount(seed.blocked());
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        disclosureJurisdictionMapper.insert(entity);
    }

    private void ensureChapter(String jurisdiction, String version, ChapterSeed seed, LocalDateTime now) {
        DisclosureChapterEntity existing = disclosureChapterMapper.selectOne(new LambdaQueryWrapper<DisclosureChapterEntity>()
                .eq(DisclosureChapterEntity::getJurisdictionCode, jurisdiction)
                .eq(DisclosureChapterEntity::getVersionLabel, version)
                .eq(DisclosureChapterEntity::getChapterNo, seed.no())
                .eq(DisclosureChapterEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        DisclosureChapterEntity entity = new DisclosureChapterEntity();
        entity.setJurisdictionCode(jurisdiction);
        entity.setVersionLabel(version);
        entity.setChapterNo(seed.no());
        entity.setZhTitle(seed.zhTitle());
        entity.setEnTitle(seed.enTitle());
        entity.setZhBody(seed.zhTitle() + "。请在继续使用相关功能前完成确认。");
        entity.setEnBody(seed.enTitle() + ". Please acknowledge before continuing related actions.");
        entity.setSortOrder(seed.sortOrder());
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        disclosureChapterMapper.insert(entity);
    }

    private void ensureGate(GateSeed seed, LocalDateTime now) {
        DisclosureGateActionEntity existing = disclosureGateActionMapper.selectOne(new LambdaQueryWrapper<DisclosureGateActionEntity>()
                .eq(DisclosureGateActionEntity::getActionKey, seed.key())
                .eq(DisclosureGateActionEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        DisclosureGateActionEntity entity = new DisclosureGateActionEntity();
        entity.setActionKey(seed.key());
        entity.setActionName(seed.name());
        entity.setDescription(seed.description());
        entity.setStatusLabel(seed.status());
        entity.setTone(seed.tone());
        entity.setActive(seed.active());
        entity.setSortOrder(seed.sortOrder());
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        disclosureGateActionMapper.insert(entity);
    }

    private TrustSectionView toTrustSection(TrustSectionEntity entity) {
        return new TrustSectionView(
                entity.getSectionKey(),
                entity.getDescription(),
                entity.getStructText(),
                entity.getVersionLabel(),
                toViewStatus(entity.getStatus()),
                entity.getLastChange(),
                entity.getRoleGate(),
                Boolean.TRUE.equals(entity.getHighSensitivity()));
    }

    private DisclosureJurisdictionView toJurisdiction(DisclosureJurisdictionEntity entity) {
        return new DisclosureJurisdictionView(
                entity.getJurisdictionCode(),
                entity.getJurisdictionName(),
                entity.getVersionLabel(),
                toViewStatus(entity.getStatus()),
                entity.getPublishedAtLabel(),
                entity.getAffectedCount() == null ? 0 : entity.getAffectedCount(),
                entity.getAckProgressPct() == null ? 0 : entity.getAckProgressPct().doubleValue(),
                entity.getBlockedCount() == null ? 0 : entity.getBlockedCount());
    }

    private DisclosureChapterView toChapter(DisclosureChapterEntity entity) {
        return new DisclosureChapterView(
                entity.getJurisdictionCode(),
                entity.getVersionLabel(),
                entity.getChapterNo(),
                entity.getZhTitle(),
                entity.getEnTitle(),
                entity.getZhBody(),
                entity.getEnBody());
    }

    private DisclosureGateActionView toGateAction(DisclosureGateActionEntity entity) {
        boolean active = !NEX_V2.equalsIgnoreCase(entity.getActionKey()) && Boolean.TRUE.equals(entity.getActive());
        return new DisclosureGateActionView(
                entity.getActionKey(),
                entity.getActionName(),
                entity.getDescription(),
                entity.getStatusLabel(),
                entity.getTone(),
                active);
    }

    private DisclosureDraftView toDraft(DisclosureDraftEntity entity) {
        return new DisclosureDraftView(
                entity.getVersionLabel(),
                entity.getJurisdictionCode(),
                entity.getLanguageScope(),
                entity.getEffectiveDate(),
                Boolean.TRUE.equals(entity.getRequiresReack()),
                entity.getZhBody(),
                entity.getEnBody(),
                toViewStatus(entity.getStatus()));
    }

    private String toViewStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT).replace('_', '-') : "";
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String normalizeUpper(String value) {
        return normalize(value).toUpperCase(Locale.ROOT);
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private static TrustSectionSeed section(String key, String description, String structText, String version, String status, String roleGate, boolean highSensitivity, String lastChange, int sortOrder) {
        return new TrustSectionSeed(key, description, structText, version, status, roleGate, highSensitivity, lastChange, sortOrder);
    }

    private static FieldSeed field(String sectionKey, String key, String value, String delta, int sortOrder) {
        return new FieldSeed(sectionKey, key, value, delta, sortOrder);
    }

    private static JurisdictionSeed jurisdiction(String code, String name, String version, String status, String publishedAt, long affected, String ackPct, long blocked) {
        return new JurisdictionSeed(code, name, version, status, publishedAt, affected, ackPct, blocked);
    }

    private static ChapterSeed chapter(String no, String zhTitle, String enTitle, int sortOrder) {
        return new ChapterSeed(no, zhTitle, enTitle, sortOrder);
    }

    private static GateSeed gate(String key, String name, String description, String status, String tone, boolean active, int sortOrder) {
        return new GateSeed(key, name, description, status, tone, active, sortOrder);
    }

    private record TrustSectionSeed(String key, String description, String structText, String version, String status, String roleGate, boolean highSensitivity, String lastChange, int sortOrder) {
    }

    private record FieldSeed(String sectionKey, String key, String value, String delta, int sortOrder) {
    }

    private record JurisdictionSeed(String code, String name, String version, String status, String publishedAt, long affected, String ackPct, long blocked) {
    }

    private record ChapterSeed(String no, String zhTitle, String enTitle, int sortOrder) {
    }

    private record GateSeed(String key, String name, String description, String status, String tone, boolean active, int sortOrder) {
    }
}
