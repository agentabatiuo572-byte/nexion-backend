package ffdd.opsconsole.content.terms.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.terms.domain.LegalTermsRepository;
import ffdd.opsconsole.content.terms.domain.LegalTermsSection;
import ffdd.opsconsole.content.terms.domain.LegalTermsVersionView;
import ffdd.opsconsole.content.terms.dto.LegalTermsDraftRequest;
import ffdd.opsconsole.content.terms.mapper.LegalTermsAckMapper;
import ffdd.opsconsole.content.terms.mapper.LegalTermsVersionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisLegalTermsRepository implements LegalTermsRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LegalTermsVersionMapper versions;
    private final LegalTermsAckMapper acknowledgements;

    @Override public List<LegalTermsVersionView> list(String locale, String jurisdiction) {
        return versions.selectList(new LambdaQueryWrapper<LegalTermsVersionEntity>()
                .eq(LegalTermsVersionEntity::getLocale, locale).eq(LegalTermsVersionEntity::getJurisdiction, jurisdiction)
                .eq(LegalTermsVersionEntity::getIsDeleted, 0).orderByDesc(LegalTermsVersionEntity::getEffectiveAt))
                .stream().map(this::view).toList();
    }
    @Override public Optional<LegalTermsVersionView> findPublished(String locale, String jurisdiction) {
        return Optional.ofNullable(versions.selectOne(new LambdaQueryWrapper<LegalTermsVersionEntity>()
                .eq(LegalTermsVersionEntity::getLocale, locale).eq(LegalTermsVersionEntity::getJurisdiction, jurisdiction)
                .eq(LegalTermsVersionEntity::getStatus, "PUBLISHED").eq(LegalTermsVersionEntity::getIsDeleted, 0)
                .orderByDesc(LegalTermsVersionEntity::getEffectiveAt).last("LIMIT 1"))).map(this::view);
    }
    @Override public Optional<LegalTermsVersionView> findVersion(String locale, String jurisdiction, String version) {
        return Optional.ofNullable(find(locale, jurisdiction, version)).map(this::view);
    }
    @Override public long currentRevision(String locale, String jurisdiction, String version) {
        LegalTermsVersionEntity row = find(locale, jurisdiction, version);
        return row == null || row.getRevision() == null ? 0L : row.getRevision();
    }
    @Override public LegalTermsVersionView saveDraft(LegalTermsDraftRequest request, long expectedRevision, String operator, LocalDateTime now) {
        LegalTermsVersionEntity row = find(request.locale(), request.jurisdiction(), request.version());
        if (row == null) {
            row = new LegalTermsVersionEntity(); row.setLocale(request.locale()); row.setJurisdiction(request.jurisdiction());
            row.setVersionLabel(request.version()); row.setStatus("DRAFT"); row.setRevision(0L); row.setCreatedAt(now); row.setIsDeleted(0);
        }
        if (!"DRAFT".equalsIgnoreCase(row.getStatus()) || !Long.valueOf(expectedRevision).equals(row.getRevision())) throw new OptimisticLockingFailureException("LEGAL_TERMS_VERSION_CONFLICT");
        row.setEffectiveAt(request.effectiveAt()); row.setTitle(request.title().trim()); row.setSummary(request.summary().trim());
        row.setSectionsJson(json(request.sections())); row.setRevision(expectedRevision + 1); row.setLastOperator(operator); row.setUpdatedAt(now);
        if (row.getId() == null) versions.insert(row); else versions.updateById(row);
        return view(row);
    }
    @Override public LegalTermsVersionView publish(String locale, String jurisdiction, String version, long expectedRevision, String operator, LocalDateTime now) {
        LegalTermsVersionEntity row = require(locale, jurisdiction, version);
        if (!Long.valueOf(expectedRevision).equals(row.getRevision()) || !"DRAFT".equalsIgnoreCase(row.getStatus())) throw new OptimisticLockingFailureException("LEGAL_TERMS_VERSION_CONFLICT");
        versions.supersede(locale, jurisdiction, now);
        row.setStatus("PUBLISHED"); row.setRevision(expectedRevision + 1); row.setPublishedAt(now); row.setLastOperator(operator); row.setUpdatedAt(now);
        if (versions.updatePublished(row, expectedRevision) != 1) throw new OptimisticLockingFailureException("LEGAL_TERMS_VERSION_CONFLICT");
        return view(row);
    }
    @Override public LegalTermsVersionView revoke(String locale, String jurisdiction, String version, long expectedRevision, String operator, LocalDateTime now) {
        LegalTermsVersionEntity row = require(locale, jurisdiction, version);
        if (!Long.valueOf(expectedRevision).equals(row.getRevision()) || !"PUBLISHED".equalsIgnoreCase(row.getStatus())) throw new OptimisticLockingFailureException("LEGAL_TERMS_VERSION_CONFLICT");
        row.setStatus("REVOKED"); row.setRevision(expectedRevision + 1); row.setRevokedAt(now); row.setLastOperator(operator); row.setUpdatedAt(now);
        if (versions.updateRevoked(row, expectedRevision) != 1) throw new OptimisticLockingFailureException("LEGAL_TERMS_VERSION_CONFLICT");
        return view(row);
    }
    @Override public Optional<LegalTermsAcknowledgement> findAck(Long userId, String sourceEnvironment, String runId, String locale, String jurisdiction) {
        return Optional.ofNullable(acknowledgements.findOne(userId, sourceEnvironment, runId, locale, jurisdiction)).map(row -> new LegalTermsAcknowledgement(row.getVersionLabel(), row.getAcknowledgedAt(), row.getIdempotencyKey()));
    }
    @Override public LegalTermsAcknowledgement saveAck(Long userId, String sourceEnvironment, String runId, String locale, String jurisdiction, String version, String idempotencyKey, LocalDateTime now) {
        LegalTermsAckEntity row = acknowledgements.findOne(userId, sourceEnvironment, runId, locale, jurisdiction);
        if (row == null) { row = new LegalTermsAckEntity(); row.setUserId(userId); row.setSourceEnvironment(sourceEnvironment); row.setRunId(runId); row.setLocale(locale); row.setJurisdiction(jurisdiction); row.setCreatedAt(now); row.setIsDeleted(0); }
        row.setVersionLabel(version); row.setIdempotencyKey(idempotencyKey); row.setAcknowledgedAt(now); row.setUpdatedAt(now);
        if (row.getId() == null) acknowledgements.insert(row); else acknowledgements.updateById(row);
        return new LegalTermsAcknowledgement(version, now, idempotencyKey);
    }
    private LegalTermsVersionEntity find(String l, String j, String v) { return versions.selectOne(new LambdaQueryWrapper<LegalTermsVersionEntity>().eq(LegalTermsVersionEntity::getLocale,l).eq(LegalTermsVersionEntity::getJurisdiction,j).eq(LegalTermsVersionEntity::getVersionLabel,v).eq(LegalTermsVersionEntity::getIsDeleted,0).last("LIMIT 1")); }
    private LegalTermsVersionEntity require(String l,String j,String v) { LegalTermsVersionEntity row=find(l,j,v); if(row==null) throw new IllegalArgumentException("LEGAL_TERMS_VERSION_NOT_FOUND"); return row; }
    private String json(List<LegalTermsSection> sections) { try{return JSON.writeValueAsString(sections);}catch(Exception ex){throw new IllegalArgumentException("LEGAL_TERMS_CONTENT_INVALID",ex);} }
    private LegalTermsVersionView view(LegalTermsVersionEntity row) { try { return new LegalTermsVersionView(row.getId(),row.getLocale(),row.getJurisdiction(),row.getVersionLabel(),row.getEffectiveAt(),row.getStatus(),row.getTitle(),row.getSummary(),JSON.readValue(row.getSectionsJson(),new TypeReference<>(){}),row.getRevision()==null?0:row.getRevision(),row.getPublishedAt(),row.getRevokedAt()); } catch(Exception ex){throw new IllegalStateException("LEGAL_TERMS_CONTENT_INVALID",ex);} }
}
