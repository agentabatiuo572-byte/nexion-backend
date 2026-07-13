package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionCatalogView;
import ffdd.opsconsole.content.domain.FinancialFieldView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustSectionFieldView;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.domain.TrustSectionVersionView;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureChapterInput;
import ffdd.opsconsole.content.dto.DisclosureMatrixRequest;
import ffdd.opsconsole.content.dto.DisclosureJurisdictionCatalogRequest;
import ffdd.opsconsole.content.dto.TrustSectionDraftRequest;
import ffdd.opsconsole.content.dto.TrustSectionFieldInput;
import ffdd.opsconsole.content.mapper.DisclosureChapterMapper;
import ffdd.opsconsole.content.mapper.DisclosureAckStatusMapper;
import ffdd.opsconsole.content.mapper.DisclosureDraftMapper;
import ffdd.opsconsole.content.mapper.DisclosureGateActionMapper;
import ffdd.opsconsole.content.mapper.DisclosureJurisdictionMapper;
import ffdd.opsconsole.content.mapper.DisclosureJurisdictionCatalogMapper;
import ffdd.opsconsole.content.mapper.TrustSectionFieldMapper;
import ffdd.opsconsole.content.mapper.TrustSectionMapper;
import ffdd.opsconsole.content.mapper.TrustSectionVersionMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisTrustDisclosureRepository implements TrustDisclosureRepository {
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final String FINANCIALS = "financials";
    private static final String NEX_V2 = "nexv2";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TrustSectionMapper trustSectionMapper;
    private final TrustSectionFieldMapper trustSectionFieldMapper;
    private final TrustSectionVersionMapper trustSectionVersionMapper;
    private final DisclosureJurisdictionMapper disclosureJurisdictionMapper;
    private final DisclosureJurisdictionCatalogMapper disclosureJurisdictionCatalogMapper;
    private final DisclosureChapterMapper disclosureChapterMapper;
    private final DisclosureGateActionMapper disclosureGateActionMapper;
    private final DisclosureDraftMapper disclosureDraftMapper;
    private final DisclosureAckStatusMapper disclosureAckStatusMapper;

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
    public List<TrustSectionVersionView> listTrustSectionVersions() {
        return trustSectionVersionMapper.selectList(new LambdaQueryWrapper<TrustSectionVersionEntity>()
                        .eq(TrustSectionVersionEntity::getIsDeleted, 0)
                        .orderByAsc(TrustSectionVersionEntity::getSectionKey)
                        .orderByDesc(TrustSectionVersionEntity::getId))
                .stream().map(this::toTrustSectionVersion).toList();
    }

    @Override
    public Optional<TrustSectionVersionView> findTrustSectionVersion(String sectionKey, String version) {
        return Optional.ofNullable(findTrustSectionVersionEntity(sectionKey, version)).map(this::toTrustSectionVersion);
    }

    @Override
    public TrustSectionVersionView saveTrustSectionDraft(String sectionKey, TrustSectionDraftRequest request, LocalDateTime now) {
        TrustSectionVersionEntity entity = findTrustSectionVersionEntity(sectionKey, request.version());
        if (entity == null) {
            entity = new TrustSectionVersionEntity();
            entity.setSectionKey(normalize(sectionKey));
            entity.setVersionLabel(normalize(request.version()));
            entity.setStatus("DRAFT");
            entity.setRevision(0L);
            entity.setCreatedAt(now);
            entity.setIsDeleted(0);
        }
        entity.setDescription(normalize(request.description()));
        entity.setStructText(normalize(request.structure()));
        try {
            entity.setFieldsJson(JSON.writeValueAsString(request.fields()));
        } catch (Exception ex) {
            throw new IllegalArgumentException("TRUST_SECTION_FIELDS_SERIALIZATION_FAILED", ex);
        }
        entity.setRevision((entity.getRevision() == null ? 0L : entity.getRevision()) + 1L);
        entity.setLastOperator(operator(request.operator()));
        entity.setUpdatedAt(now);
        if (entity.getId() == null) trustSectionVersionMapper.insert(entity); else trustSectionVersionMapper.updateById(entity);
        return toTrustSectionVersion(entity);
    }

    @Override
    public void deleteTrustSectionDraft(String sectionKey, String version, LocalDateTime now) {
        TrustSectionVersionEntity entity = findTrustSectionVersionEntity(sectionKey, version);
        if (entity == null) return;
        // v999999999(10) + "~d~"(3) + signed BIGINT max(19) fits version_label VARCHAR(32).
        entity.setVersionLabel(normalize(version) + "~d~" + entity.getId());
        entity.setIsDeleted(1);
        entity.setUpdatedAt(now);
        trustSectionVersionMapper.updateById(entity);
    }

    @Override
    public TrustSectionView publishTrustSectionVersion(String sectionKey, String version, String operator, LocalDateTime now) {
        TrustSectionVersionEntity target = findTrustSectionVersionEntity(sectionKey, version);
        if (target == null) throw new IllegalArgumentException("TRUST_SECTION_VERSION_NOT_FOUND");
        for (TrustSectionVersionEntity row : trustSectionVersionMapper.selectList(new LambdaQueryWrapper<TrustSectionVersionEntity>()
                .eq(TrustSectionVersionEntity::getSectionKey, normalize(sectionKey)).eq(TrustSectionVersionEntity::getIsDeleted, 0))) {
            row.setStatus(row.getId().equals(target.getId()) ? "PUBLISHED" : ("PUBLISHED".equalsIgnoreCase(row.getStatus()) ? "SUPERSEDED" : row.getStatus()));
            row.setUpdatedAt(now);
            trustSectionVersionMapper.updateById(row);
        }
        TrustSectionEntity section = findTrustSectionEntity(sectionKey);
        if (section == null) throw new IllegalArgumentException("TRUST_SECTION_NOT_FOUND");
        section.setDescription(target.getDescription());
        section.setStructText(target.getStructText());
        section.setVersionLabel(target.getVersionLabel());
        section.setStatus("PUBLISHED");
        section.setLastChange(DAY_LABEL.format(now));
        section.setLastOperator(operator(operator));
        section.setUpdatedAt(now);
        trustSectionMapper.updateById(section);
        replaceSectionFields(sectionKey, readFields(target.getFieldsJson()), operator, now);
        return toTrustSection(section);
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
    public List<DisclosureJurisdictionCatalogView> listJurisdictionCatalog() {
        return disclosureJurisdictionCatalogMapper.selectList(
                        new LambdaQueryWrapper<DisclosureJurisdictionCatalogEntity>()
                                .eq(DisclosureJurisdictionCatalogEntity::getIsDeleted, 0)
                                .orderByAsc(DisclosureJurisdictionCatalogEntity::getId))
                .stream().map(this::toJurisdictionCatalog).toList();
    }

    @Override
    public List<DisclosureJurisdictionCatalogView> listActiveJurisdictionCatalog() {
        return disclosureJurisdictionCatalogMapper.selectList(
                        new LambdaQueryWrapper<DisclosureJurisdictionCatalogEntity>()
                                .eq(DisclosureJurisdictionCatalogEntity::getIsDeleted, 0)
                                .eq(DisclosureJurisdictionCatalogEntity::getStatus, "ACTIVE")
                                .orderByAsc(DisclosureJurisdictionCatalogEntity::getId))
                .stream()
                .map(entity -> new DisclosureJurisdictionCatalogView(
                        entity.getJurisdictionCode(), entity.getJurisdictionName(), entity.getStatus(),
                        entity.getRevision() == null ? 0L : entity.getRevision(), 0L, false,
                        operator(entity.getLastOperator()),
                        entity.getUpdatedAt() == null ? "" : entity.getUpdatedAt().toString()))
                .toList();
    }

    @Override
    public Optional<DisclosureJurisdictionCatalogView> findJurisdictionCatalog(String jurisdiction) {
        return Optional.ofNullable(findJurisdictionCatalogEntity(jurisdiction, false)).map(this::toJurisdictionCatalog);
    }

    @Override
    public boolean jurisdictionCatalogCodeExists(String jurisdiction) {
        return findJurisdictionCatalogEntity(jurisdiction, true) != null;
    }

    @Override
    public DisclosureJurisdictionCatalogView createJurisdictionCatalog(
            DisclosureJurisdictionCatalogRequest request, String operator, LocalDateTime now) {
        DisclosureJurisdictionCatalogEntity entity = new DisclosureJurisdictionCatalogEntity();
        entity.setJurisdictionCode(normalizeUpper(request.code()));
        entity.setJurisdictionName(normalize(request.name()));
        entity.setStatus("DISABLED");
        entity.setRevision(1L);
        entity.setLastOperator(operator(operator));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        disclosureJurisdictionCatalogMapper.insert(entity);
        return toJurisdictionCatalog(entity);
    }

    @Override
    public DisclosureJurisdictionCatalogView updateJurisdictionCatalog(
            String jurisdiction, DisclosureJurisdictionCatalogRequest request, String operator, LocalDateTime now) {
        int updated = disclosureJurisdictionCatalogMapper.updateNameOptimistically(
                normalizeUpper(jurisdiction), normalize(request.name()), request.expectedRevision(), operator(operator), now);
        if (updated != 1) throw new org.springframework.dao.OptimisticLockingFailureException("DISCLOSURE_JURISDICTION_REVISION_CONFLICT");
        return toJurisdictionCatalog(findJurisdictionCatalogEntity(jurisdiction, false));
    }

    @Override
    public DisclosureJurisdictionCatalogView changeJurisdictionCatalogStatus(
            String jurisdiction, String status, long expectedRevision, String operator, LocalDateTime now) {
        int updated = disclosureJurisdictionCatalogMapper.updateStatusOptimistically(
                normalizeUpper(jurisdiction), normalizeUpper(status), expectedRevision, operator(operator), now);
        if (updated != 1) throw new org.springframework.dao.OptimisticLockingFailureException("DISCLOSURE_JURISDICTION_REVISION_CONFLICT");
        return toJurisdictionCatalog(findJurisdictionCatalogEntity(jurisdiction, false));
    }

    @Override
    public void deleteJurisdictionCatalog(String jurisdiction, long expectedRevision, String operator, LocalDateTime now) {
        int deleted = disclosureJurisdictionCatalogMapper.softDeleteOptimistically(
                normalizeUpper(jurisdiction), expectedRevision, operator(operator), now);
        if (deleted != 1) throw new org.springframework.dao.OptimisticLockingFailureException("DISCLOSURE_JURISDICTION_REVISION_CONFLICT");
    }

    @Override
    public List<String> listDisclosureVersions() {
        Set<String> versions = new TreeSet<>();
        disclosureJurisdictionMapper.selectList(new LambdaQueryWrapper<DisclosureJurisdictionEntity>().eq(DisclosureJurisdictionEntity::getIsDeleted, 0))
                .forEach(entity -> addVersion(versions, entity.getVersionLabel()));
        disclosureDraftMapper.selectList(new LambdaQueryWrapper<DisclosureDraftEntity>().eq(DisclosureDraftEntity::getIsDeleted, 0))
                .forEach(entity -> addVersion(versions, entity.getVersionLabel()));
        disclosureChapterMapper.selectList(new LambdaQueryWrapper<DisclosureChapterEntity>().eq(DisclosureChapterEntity::getIsDeleted, 0))
                .forEach(entity -> addVersion(versions, entity.getVersionLabel()));
        return List.copyOf(versions);
    }

    @Override
    public List<String> listDisclosureVersionsIncludingDeleted(String jurisdiction) {
        Set<String> versions = new TreeSet<>();
        disclosureDraftMapper.selectList(new LambdaQueryWrapper<DisclosureDraftEntity>()
                        .eq(DisclosureDraftEntity::getJurisdictionCode, normalizeUpper(jurisdiction)))
                .forEach(entity -> addVersion(versions, entity.getVersionLabel()));
        DisclosureJurisdictionEntity current = findJurisdictionEntity(jurisdiction);
        if (current != null) addVersion(versions, current.getVersionLabel());
        return List.copyOf(versions);
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
                        .eq(DisclosureDraftEntity::getStatus, "DRAFT")
                        .orderByDesc(DisclosureDraftEntity::getId)
                        .last("LIMIT 1")))
                .map(this::toDraft);
    }

    @Override
    public Optional<DisclosureDraftView> findDraft(String jurisdiction) {
        return Optional.ofNullable(disclosureDraftMapper.selectOne(new LambdaQueryWrapper<DisclosureDraftEntity>()
                        .eq(DisclosureDraftEntity::getIsDeleted, 0)
                        .eq(DisclosureDraftEntity::getJurisdictionCode, normalizeUpper(jurisdiction))
                        .eq(DisclosureDraftEntity::getStatus, "DRAFT")
                        .orderByDesc(DisclosureDraftEntity::getId)
                        .last("LIMIT 1")))
                .map(this::toDraft);
    }

    @Override
    public Optional<DisclosureDraftView> findDisclosureVersion(String jurisdiction, String version) {
        return Optional.ofNullable(findDraftEntity(jurisdiction, version)).map(this::toDraft);
    }

    @Override
    public List<DisclosureDraftView> listDisclosureVersionItems() {
        return disclosureDraftMapper.selectList(new LambdaQueryWrapper<DisclosureDraftEntity>()
                        .eq(DisclosureDraftEntity::getIsDeleted, 0)
                        .orderByAsc(DisclosureDraftEntity::getJurisdictionCode)
                        .orderByDesc(DisclosureDraftEntity::getVersionLabel))
                .stream().map(this::toDraft).toList();
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
    public DisclosureDraftView saveDisclosureDraft(DisclosureDraftRequest request, String status, String contentHash, LocalDateTime now) {
        DisclosureDraftEntity draft = findDraftEntity(request.jurisdiction(), request.version());
        boolean inserting = draft == null;
        if (draft == null) {
            draft = new DisclosureDraftEntity();
            draft.setJurisdictionCode(normalizeUpper(request.jurisdiction()));
            draft.setVersionLabel(normalize(request.version()));
            draft.setCreatedAt(now);
            draft.setIsDeleted(0);
            draft.setRevision(1L);
        }
        draft.setLanguageScope(normalize(request.languageScope()));
        draft.setEffectiveDate(normalize(request.effectiveDate()));
        draft.setRequiresReack(Boolean.TRUE.equals(request.requiresReack()));
        draft.setZhBody(normalize(request.zh()));
        draft.setViBody(normalize(request.vi()));
        draft.setEnBody(normalize(request.en()));
        draft.setStatus(normalizeUpper(status));
        draft.setContentHash(contentHash);
        draft.setLastOperator(operator(request.operator()));
        draft.setUpdatedAt(now);
        if (inserting) {
            disclosureDraftMapper.insert(draft);
        } else {
            if (request.expectedRevision() == null || !StringUtils.hasText(request.expectedContentHash())) {
                throw new org.springframework.dao.OptimisticLockingFailureException("DISCLOSURE_DRAFT_REVISION_REQUIRED");
            }
            int updated = disclosureDraftMapper.updateDraftOptimistically(
                    draft, request.expectedRevision(), request.expectedContentHash().trim(), contentHash, now);
            if (updated != 1) {
                throw new org.springframework.dao.OptimisticLockingFailureException("DISCLOSURE_DRAFT_REVISION_CONFLICT");
            }
        }
        replaceDisclosureChapters(normalizeUpper(request.jurisdiction()), normalize(request.version()), request, now);
        return toDraft(findDraftEntity(request.jurisdiction(), request.version()));
    }

    @Override
    public void deleteDisclosureDraft(String jurisdiction, String version, long expectedRevision,
                                      String expectedContentHash, LocalDateTime now) {
        int deleted = disclosureDraftMapper.softDeleteDraftOptimistically(
                normalizeUpper(jurisdiction), normalize(version), expectedRevision, expectedContentHash, now);
        if (deleted != 1) {
            throw new org.springframework.dao.OptimisticLockingFailureException("DISCLOSURE_DRAFT_REVISION_CONFLICT");
        }
        disclosureChapterMapper.delete(new LambdaQueryWrapper<DisclosureChapterEntity>()
                .eq(DisclosureChapterEntity::getJurisdictionCode, normalizeUpper(jurisdiction))
                .eq(DisclosureChapterEntity::getVersionLabel, normalize(version)));
    }

    @Override
    public void lockDisclosureJurisdiction(String jurisdiction) {
        disclosureJurisdictionMapper.selectForUpdate(normalizeUpper(jurisdiction));
    }

    @Override
    public void lockJurisdictionCatalog(String jurisdiction) {
        disclosureJurisdictionCatalogMapper.selectAnyForUpdate(normalizeUpper(jurisdiction));
    }

    @Override
    public void lockAllJurisdictionCatalogs() {
        disclosureJurisdictionCatalogMapper.selectAllIdsForUpdate();
    }

    @Override
    public void publishDisclosure(String jurisdiction, DisclosureDraftRequest request, LocalDateTime now) {
        disclosureDraftMapper.selectList(new LambdaQueryWrapper<DisclosureDraftEntity>()
                        .eq(DisclosureDraftEntity::getJurisdictionCode, normalizeUpper(jurisdiction))
                        .eq(DisclosureDraftEntity::getStatus, "PUBLISHED")
                        .eq(DisclosureDraftEntity::getIsDeleted, 0))
                .forEach(row -> {
                    row.setStatus("SUPERSEDED");
                    row.setUpdatedAt(now);
                    disclosureDraftMapper.updateById(row);
                });
        DisclosureDraftEntity target = findDraftEntity(request.jurisdiction(), request.version());
        if (target == null || !"DRAFT".equalsIgnoreCase(target.getStatus())) {
            throw new org.springframework.dao.OptimisticLockingFailureException("DISCLOSURE_DRAFT_VERSION_NOT_FOUND");
        }
        target.setStatus("PUBLISHED");
        target.setLastOperator(operator(request.operator()));
        target.setUpdatedAt(now);
        disclosureDraftMapper.updateById(target);
    }

    @Override
    public void upsertDisclosureMatrix(DisclosureMatrixRequest request, LocalDateTime now) {
        DisclosureJurisdictionEntity entity = findJurisdictionEntity(request.jurisdictionCode());
        if (entity == null) {
            entity = new DisclosureJurisdictionEntity();
            entity.setJurisdictionCode(normalizeUpper(request.jurisdictionCode()));
            entity.setAffectedCount(0L); entity.setAckProgressPct(BigDecimal.ZERO); entity.setBlockedCount(0L);
            entity.setPublishedAtLabel(""); entity.setCreatedAt(now); entity.setIsDeleted(0);
        }
        entity.setJurisdictionName(normalize(request.jurisdictionName()));
        entity.setCountryCodes(String.join(",", request.countryCodes().stream()
                .filter(StringUtils::hasText).map(this::normalizeUpper).distinct().sorted().toList()));
        entity.setVersionLabel(normalize(request.version()));
        entity.setStatus(normalizeUpper(request.status()));
        entity.setLastOperator(operator(request.operator())); entity.setUpdatedAt(now);
        if (entity.getId() == null) disclosureJurisdictionMapper.insert(entity); else disclosureJurisdictionMapper.updateById(entity);
    }

    @Override
    public void markDisclosureMatrixUsersStale(
            String jurisdiction, List<String> countryCodes, String version, LocalDateTime now) {
        List<String> normalizedCountries = countryCodes == null ? List.of() : countryCodes.stream()
                .filter(StringUtils::hasText).map(this::normalizeUpper).distinct().sorted().toList();
        if (!normalizedCountries.isEmpty()) {
            disclosureAckStatusMapper.markJurisdictionUsersStale(
                    normalizeUpper(jurisdiction), normalizedCountries, normalize(version), now);
        }
    }

    @Override
    public void archiveDisclosureMatrix(String jurisdiction, String operator, LocalDateTime now) {
        DisclosureJurisdictionEntity entity = findJurisdictionEntity(jurisdiction);
        if (entity == null) return;
        entity.setStatus("ARCHIVED"); entity.setLastOperator(operator(operator)); entity.setUpdatedAt(now);
        disclosureJurisdictionMapper.updateById(entity);
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

    private void replaceDisclosureChapters(String jurisdiction, String version, DisclosureDraftRequest request, LocalDateTime now) {
        disclosureChapterMapper.delete(new LambdaQueryWrapper<DisclosureChapterEntity>()
                .eq(DisclosureChapterEntity::getJurisdictionCode, jurisdiction)
                .eq(DisclosureChapterEntity::getVersionLabel, version));
        List<DisclosureChapterInput> source = request.chapters();
        for (int index = 0; index < source.size(); index += 1) {
            DisclosureChapterInput current = source.get(index);
            DisclosureChapterEntity chapter = new DisclosureChapterEntity();
            chapter.setJurisdictionCode(jurisdiction);
            chapter.setVersionLabel(version);
            chapter.setChapterNo(normalize(current.no()));
            chapter.setZhTitle(normalize(current.zhTitle()));
            chapter.setViTitle(normalize(current.viTitle()));
            chapter.setEnTitle(normalize(current.enTitle()));
            chapter.setZhBody(normalize(current.zhBody()));
            chapter.setViBody(normalize(current.viBody()));
            chapter.setEnBody(normalize(current.enBody()));
            chapter.setSortOrder((index + 1) * 10);
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

    private TrustSectionVersionEntity findTrustSectionVersionEntity(String sectionKey, String version) {
        return trustSectionVersionMapper.selectOne(new LambdaQueryWrapper<TrustSectionVersionEntity>()
                .eq(TrustSectionVersionEntity::getSectionKey, normalize(sectionKey))
                .eq(TrustSectionVersionEntity::getVersionLabel, normalize(version))
                .eq(TrustSectionVersionEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    void replaceSectionFields(String sectionKey, List<TrustSectionFieldInput> fields, String operator, LocalDateTime now) {
        List<TrustSectionFieldEntity> existing = trustSectionFieldMapper.selectList(new LambdaQueryWrapper<TrustSectionFieldEntity>()
                .eq(TrustSectionFieldEntity::getSectionKey, normalize(sectionKey)));
        Map<String, TrustSectionFieldEntity> existingByKey = new LinkedHashMap<>();
        existing.forEach(row -> existingByKey.put(normalize(row.getFieldKey()).toLowerCase(Locale.ROOT), row));
        Set<String> incomingKeys = fields.stream()
                .map(field -> normalize(field.key()).toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (TrustSectionFieldEntity row : existing) {
            if (!incomingKeys.contains(normalize(row.getFieldKey()).toLowerCase(Locale.ROOT))
                    && !Integer.valueOf(1).equals(row.getIsDeleted())) {
                row.setIsDeleted(1);
                row.setLastOperator(operator(operator));
                row.setUpdatedAt(now);
                trustSectionFieldMapper.updateById(row);
            }
        }
        for (int index = 0; index < fields.size(); index += 1) {
            TrustSectionFieldInput field = fields.get(index);
            String fieldKey = normalize(field.key());
            TrustSectionFieldEntity row = existingByKey.get(fieldKey.toLowerCase(Locale.ROOT));
            boolean inserting = row == null;
            if (inserting) {
                row = new TrustSectionFieldEntity();
                row.setSectionKey(normalize(sectionKey));
                row.setFieldKey(fieldKey);
                row.setCreatedAt(now);
            }
            row.setFieldValue(normalize(field.value()));
            row.setFieldDelta(normalize(field.label()));
            row.setSortOrder((index + 1) * 10);
            row.setLastOperator(operator(operator));
            row.setUpdatedAt(now);
            row.setIsDeleted(0);
            if (inserting) trustSectionFieldMapper.insert(row); else trustSectionFieldMapper.updateById(row);
        }
    }

    private List<TrustSectionFieldInput> readFields(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private TrustSectionVersionView toTrustSectionVersion(TrustSectionVersionEntity entity) {
        return new TrustSectionVersionView(
                entity.getSectionKey(), entity.getVersionLabel(), entity.getDescription(), entity.getStructText(),
                readFields(entity.getFieldsJson()).stream().map(field -> new TrustSectionVersionView.Field(field.key(), field.label(), field.value())).toList(),
                normalize(entity.getStatus()), entity.getRevision() == null ? 0L : entity.getRevision(),
                operator(entity.getLastOperator()), entity.getUpdatedAt() == null ? "" : entity.getUpdatedAt().toString());
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
        entity.setViTitle(seed.zhTitle());
        entity.setEnTitle(seed.enTitle());
        entity.setZhBody(seed.zhTitle() + "。请在继续使用相关功能前完成确认。");
        entity.setViBody(seed.zhTitle() + ". Vui lòng xác nhận trước khi tiếp tục.");
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
        long affected = disclosureAckStatusMapper.countAffected(entity.getJurisdictionCode());
        long acknowledged = affected == 0 ? 0 : disclosureAckStatusMapper.countAcknowledged(entity.getJurisdictionCode());
        double ackProgress = affected == 0 ? 0D : Math.round(acknowledged * 10000D / affected) / 100D;
        return new DisclosureJurisdictionView(
                entity.getJurisdictionCode(),
                entity.getJurisdictionName(),
                countryCodes(entity.getCountryCodes()),
                entity.getVersionLabel(),
                toViewStatus(entity.getStatus()),
                entity.getPublishedAtLabel(),
                affected,
                ackProgress,
                entity.getBlockedCount() == null ? 0 : entity.getBlockedCount());
    }

    private DisclosureChapterView toChapter(DisclosureChapterEntity entity) {
        return new DisclosureChapterView(
                entity.getJurisdictionCode(),
                entity.getVersionLabel(),
                entity.getChapterNo(),
                entity.getZhTitle(),
                entity.getViTitle(),
                entity.getEnTitle(),
                entity.getZhBody(),
                entity.getViBody(),
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
                entity.getViBody(),
                entity.getEnBody(),
                toViewStatus(entity.getStatus()),
                entity.getRevision() == null ? 1L : entity.getRevision(),
                normalize(entity.getContentHash()));
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

    private DisclosureJurisdictionCatalogEntity findJurisdictionCatalogEntity(String jurisdiction, boolean includeDeleted) {
        return disclosureJurisdictionCatalogMapper.selectOne(
                new LambdaQueryWrapper<DisclosureJurisdictionCatalogEntity>()
                        .eq(DisclosureJurisdictionCatalogEntity::getJurisdictionCode, normalizeUpper(jurisdiction))
                        .eq(!includeDeleted, DisclosureJurisdictionCatalogEntity::getIsDeleted, 0)
                        .last("LIMIT 1"));
    }

    private DisclosureJurisdictionCatalogView toJurisdictionCatalog(DisclosureJurisdictionCatalogEntity entity) {
        if (entity == null) return null;
        return new DisclosureJurisdictionCatalogView(
                entity.getJurisdictionCode(), entity.getJurisdictionName(), normalizeUpper(entity.getStatus()),
                entity.getRevision() == null ? 0L : entity.getRevision(),
                disclosureJurisdictionCatalogMapper.countVersionReferences(entity.getJurisdictionCode()),
                disclosureJurisdictionCatalogMapper.hasActiveMapping(entity.getJurisdictionCode()),
                operator(entity.getLastOperator()), entity.getUpdatedAt() == null ? "" : entity.getUpdatedAt().toString());
    }

    private void addVersion(Set<String> versions, String value) {
        if (StringUtils.hasText(value)) versions.add(value.trim());
    }

    private List<String> countryCodes(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        return Arrays.stream(value.split(","))
                .map(this::normalizeUpper)
                .filter(StringUtils::hasText)
                .distinct()
                .sorted()
                .toList();
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
