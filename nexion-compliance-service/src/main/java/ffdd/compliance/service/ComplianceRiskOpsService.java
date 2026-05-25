package ffdd.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import ffdd.common.exception.BizException;
import ffdd.compliance.domain.RiskBlacklist;
import ffdd.compliance.domain.RiskDecision;
import ffdd.compliance.dto.RiskBlacklistReleaseRequest;
import ffdd.compliance.dto.RiskBlacklistUpsertRequest;
import ffdd.compliance.dto.RiskDecisionSummaryResponse;
import ffdd.compliance.mapper.RiskBlacklistMapper;
import ffdd.compliance.mapper.RiskDecisionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ComplianceRiskOpsService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RELEASED = "RELEASED";
    private static final String DECISION_APPROVE = "APPROVE";
    private static final String DECISION_REVIEW = "REVIEW";
    private static final String DECISION_REJECT = "REJECT";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;

    private final RiskDecisionMapper riskDecisionMapper;
    private final RiskBlacklistMapper riskBlacklistMapper;

    public ComplianceRiskOpsService(RiskDecisionMapper riskDecisionMapper, RiskBlacklistMapper riskBlacklistMapper) {
        this.riskDecisionMapper = riskDecisionMapper;
        this.riskBlacklistMapper = riskBlacklistMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskBlacklist upsertBlacklist(RiskBlacklistUpsertRequest request) {
        validateUpsert(request);
        RiskBlacklist existing = findBlacklistAny(request.getUserId());
        if (existing == null) {
            return insertBlacklist(request);
        }

        String source = normalizeSource(request.getSource());
        String riskLevel = normalizeRiskLevel(request.getRiskLevel());
        LambdaUpdateWrapper<RiskBlacklist> updateWrapper = new LambdaUpdateWrapper<RiskBlacklist>()
                .eq(RiskBlacklist::getId, existing.getId())
                .set(RiskBlacklist::getUserId, request.getUserId())
                .set(RiskBlacklist::getReason, request.getReason())
                .set(RiskBlacklist::getStatus, STATUS_ACTIVE)
                .set(RiskBlacklist::getSource, source)
                .set(RiskBlacklist::getRiskLevel, riskLevel)
                .set(RiskBlacklist::getExpiresAt, request.getExpiresAt())
                .set(RiskBlacklist::getCreatedBy, request.getOperator())
                .set(RiskBlacklist::getReleasedBy, null)
                .set(RiskBlacklist::getReleaseReason, null)
                .set(RiskBlacklist::getReleasedAt, null)
                .set(RiskBlacklist::getIsDeleted, 0);
        riskBlacklistMapper.update(null, updateWrapper);

        existing.setReason(request.getReason());
        existing.setStatus(STATUS_ACTIVE);
        existing.setSource(source);
        existing.setRiskLevel(riskLevel);
        existing.setExpiresAt(request.getExpiresAt());
        existing.setCreatedBy(request.getOperator());
        existing.setReleasedBy(null);
        existing.setReleaseReason(null);
        existing.setReleasedAt(null);
        existing.setIsDeleted(0);
        return existing;
    }

    @Transactional(rollbackFor = Exception.class)
    public RiskBlacklist releaseBlacklist(Long userId, RiskBlacklistReleaseRequest request) {
        validateRelease(userId, request);
        RiskBlacklist active = findActiveBlacklist(userId);
        if (active == null) {
            throw new BizException("Active blacklist entry not found");
        }

        RiskBlacklist patch = new RiskBlacklist();
        patch.setId(active.getId());
        patch.setStatus(STATUS_RELEASED);
        patch.setReleasedBy(request.getOperator());
        patch.setReleaseReason(request.getReason());
        patch.setReleasedAt(LocalDateTime.now());
        riskBlacklistMapper.updateById(patch);

        active.setStatus(patch.getStatus());
        active.setReleasedBy(patch.getReleasedBy());
        active.setReleaseReason(patch.getReleaseReason());
        active.setReleasedAt(patch.getReleasedAt());
        return active;
    }

    public List<RiskBlacklist> listBlacklists(String status, int limit) {
        String normalizedStatus = normalizeOptionalStatus(status);
        LambdaQueryWrapper<RiskBlacklist> wrapper = new LambdaQueryWrapper<RiskBlacklist>()
                .eq(RiskBlacklist::getIsDeleted, 0)
                .eq(StringUtils.hasText(normalizedStatus), RiskBlacklist::getStatus, normalizedStatus)
                .orderByDesc(RiskBlacklist::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit));
        return riskBlacklistMapper.selectList(wrapper);
    }

    public List<RiskDecision> listRiskDecisions(
            Long userId, String bizType, String decision, String reason, int limit) {
        String normalizedDecision = normalizeOptionalDecision(decision);
        validateQueryToken("bizType", bizType, 64);
        validateQueryToken("reason", reason, 255);
        LambdaQueryWrapper<RiskDecision> wrapper = new LambdaQueryWrapper<RiskDecision>()
                .eq(RiskDecision::getIsDeleted, 0)
                .eq(userId != null, RiskDecision::getUserId, userId)
                .eq(StringUtils.hasText(bizType), RiskDecision::getBizType, bizType)
                .eq(StringUtils.hasText(normalizedDecision), RiskDecision::getDecision, normalizedDecision)
                .eq(StringUtils.hasText(reason), RiskDecision::getReason, reason)
                .orderByDesc(RiskDecision::getCreatedAt)
                .last("LIMIT " + normalizeLimit(limit));
        return riskDecisionMapper.selectList(wrapper);
    }

    public RiskDecisionSummaryResponse summarize(int days) {
        int normalizedDays = normalizeDays(days);
        LocalDateTime since = LocalDateTime.now().minusDays(normalizedDays - 1L).toLocalDate().atStartOfDay();

        RiskDecisionSummaryResponse response = new RiskDecisionSummaryResponse();
        response.setDays(normalizedDays);
        response.setTotalDecisions(countDecisionsSince(since, null));
        response.setApprovedDecisions(countDecisionsSince(since, DECISION_APPROVE));
        response.setReviewDecisions(countDecisionsSince(since, DECISION_REVIEW));
        response.setRejectedDecisions(countDecisionsSince(since, DECISION_REJECT));
        response.setActiveBlacklists(countActiveBlacklists());
        return response;
    }

    private RiskBlacklist insertBlacklist(RiskBlacklistUpsertRequest request) {
        RiskBlacklist blacklist = new RiskBlacklist();
        blacklist.setUserId(request.getUserId());
        blacklist.setReason(request.getReason());
        blacklist.setStatus(STATUS_ACTIVE);
        blacklist.setSource(normalizeSource(request.getSource()));
        blacklist.setRiskLevel(normalizeRiskLevel(request.getRiskLevel()));
        blacklist.setExpiresAt(request.getExpiresAt());
        blacklist.setCreatedBy(request.getOperator());
        blacklist.setIsDeleted(0);
        try {
            riskBlacklistMapper.insert(blacklist);
            return blacklist;
        } catch (DuplicateKeyException ex) {
            RiskBlacklist duplicate = findBlacklistAny(request.getUserId());
            if (duplicate == null) {
                throw new BizException("Duplicate blacklist entry exists in an invalid state");
            }
            return duplicate;
        }
    }

    private long countDecisionsSince(LocalDateTime since, String decision) {
        Long count = riskDecisionMapper.selectCount(new LambdaQueryWrapper<RiskDecision>()
                .eq(RiskDecision::getIsDeleted, 0)
                .eq(StringUtils.hasText(decision), RiskDecision::getDecision, decision)
                .ge(RiskDecision::getCreatedAt, since));
        return count == null ? 0 : count;
    }

    private long countActiveBlacklists() {
        Long count = riskBlacklistMapper.selectCount(activeBlacklistWrapper());
        return count == null ? 0 : count;
    }

    private LambdaQueryWrapper<RiskBlacklist> activeBlacklistWrapper() {
        LocalDateTime now = LocalDateTime.now();
        return new LambdaQueryWrapper<RiskBlacklist>()
                .eq(RiskBlacklist::getStatus, STATUS_ACTIVE)
                .eq(RiskBlacklist::getIsDeleted, 0)
                .and(wrapper -> wrapper.isNull(RiskBlacklist::getExpiresAt)
                        .or()
                        .gt(RiskBlacklist::getExpiresAt, now));
    }

    private RiskBlacklist findBlacklistAny(Long userId) {
        return riskBlacklistMapper.selectOne(new LambdaQueryWrapper<RiskBlacklist>()
                .eq(RiskBlacklist::getUserId, userId));
    }

    private RiskBlacklist findActiveBlacklist(Long userId) {
        return riskBlacklistMapper.selectOne(new LambdaQueryWrapper<RiskBlacklist>()
                .eq(RiskBlacklist::getUserId, userId)
                .eq(RiskBlacklist::getStatus, STATUS_ACTIVE)
                .eq(RiskBlacklist::getIsDeleted, 0));
    }

    private void validateUpsert(RiskBlacklistUpsertRequest request) {
        if (request == null) {
            throw new BizException("Blacklist request is required");
        }
        if (request.getUserId() == null) {
            throw new BizException("User id is required");
        }
        validateRequiredText("Blacklist reason", request.getReason(), 255);
        validateRequiredText("Operator", request.getOperator(), 64);
        validateQueryToken("source", request.getSource(), 64);
        normalizeRiskLevel(request.getRiskLevel());
        if (request.getExpiresAt() != null && !request.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BizException("Blacklist expiry must be in the future");
        }
    }

    private void validateRelease(Long userId, RiskBlacklistReleaseRequest request) {
        if (userId == null) {
            throw new BizException("User id is required");
        }
        if (request == null) {
            throw new BizException("Blacklist release request is required");
        }
        validateRequiredText("Operator", request.getOperator(), 64);
        validateRequiredText("Release reason", request.getReason(), 255);
    }

    private void validateRequiredText(String fieldName, String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(fieldName + " is required");
        }
        if (value.length() > maxLength) {
            throw new BizException(fieldName + " is too long");
        }
    }

    private void validateQueryToken(String fieldName, String value, int maxLength) {
        if (StringUtils.hasText(value) && value.length() > maxLength) {
            throw new BizException(fieldName + " is too long");
        }
    }

    private String normalizeSource(String source) {
        return StringUtils.hasText(source) ? source.toUpperCase(Locale.ROOT) : "OPS";
    }

    private String normalizeRiskLevel(String riskLevel) {
        String normalized = StringUtils.hasText(riskLevel) ? riskLevel.toUpperCase(Locale.ROOT) : "HIGH";
        if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(normalized)) {
            throw new BizException("Unsupported risk level: " + riskLevel);
        }
        return normalized;
    }

    private String normalizeOptionalStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_ACTIVE, STATUS_RELEASED).contains(normalized)) {
            throw new BizException("Unsupported blacklist status: " + status);
        }
        return normalized;
    }

    private String normalizeOptionalDecision(String decision) {
        if (!StringUtils.hasText(decision)) {
            return null;
        }
        String normalized = decision.toUpperCase(Locale.ROOT);
        if (!List.of(DECISION_APPROVE, DECISION_REVIEW, DECISION_REJECT).contains(normalized)) {
            throw new BizException("Unsupported risk decision: " + decision);
        }
        return normalized;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int normalizeDays(int days) {
        if (days < 1) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }
}
