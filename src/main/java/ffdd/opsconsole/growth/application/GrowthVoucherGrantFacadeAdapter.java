package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.mapper.GrowthVoucherGrantMapper;
import ffdd.opsconsole.growth.mapper.GrowthVoucherMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** MySQL-backed H7 ownership grant boundary. */
@Service
@RequiredArgsConstructor
public class GrowthVoucherGrantFacadeAdapter implements VoucherGrantFacade {

    private static final int MAX_REASON_LENGTH = 200;

    private final GrowthVoucherMapper voucherMapper;
    private final GrowthVoucherGrantMapper mapper;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;

    @PostConstruct
    void ensureGrantTable() {
        voucherMapper.ensureTable();
        mapper.ensureGrantTable();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VoucherGrantResult grant(VoucherGrantCommand command) {
        ValidGrant request = validate(command);
        if (mapper.lockActiveUser(request.userId()) == null) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "H7_VOUCHER_USER_NOT_ACTIVE");
        }
        if (mapper.lockGrantableVoucher(request.voucherId(), System.currentTimeMillis()) == null) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "H7_VOUCHER_NOT_GRANTABLE");
        }

        String grantId = "VGR-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        int inserted = mapper.insertGrant(
                grantId,
                request.grantKey(),
                request.voucherId(),
                request.userId(),
                request.sourceType(),
                request.sourceId(),
                request.operator(),
                request.reason());
        if (inserted == 0) {
            Map<String, Object> existing = mapper.findByGrantKey(request.grantKey());
            if (sameGrant(existing, request)) {
                return new VoucherGrantResult(String.valueOf(existing.get("grantId")), true);
            }
            throw new BizException(409, "H7_VOUCHER_GRANT_IDEMPOTENCY_CONFLICT");
        }

        Map<String, Object> detail = Map.of(
                "grantId", grantId,
                "grantKey", request.grantKey(),
                "voucherId", request.voucherId(),
                "userId", request.userId(),
                "sourceType", request.sourceType(),
                "sourceId", request.sourceId(),
                "reason", request.reason());
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("H7_VOUCHER_GRANTED")
                .resourceType("USER_VOUCHER_GRANT")
                .resourceId(grantId)
                .bizNo(request.grantKey())
                .userId(request.userId())
                .actorType("ADMIN")
                .actorUsername(request.operator())
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
        outboxService.publish("VOUCHER_GRANT", grantId, "H7_VOUCHER_GRANTED", detail);
        return new VoucherGrantResult(grantId, false);
    }

    private ValidGrant validate(VoucherGrantCommand command) {
        if (command == null || command.userId() == null || command.userId() <= 0) {
            throw validation("H7_VOUCHER_USER_REQUIRED");
        }
        String sourceType = required(command.sourceType(), 32, "H7_VOUCHER_SOURCE_TYPE_REQUIRED")
                .toUpperCase(Locale.ROOT);
        if (!sourceType.matches("^[A-Z][A-Z0-9_]*$")) {
            throw validation("H7_VOUCHER_SOURCE_TYPE_INVALID");
        }
        String grantKey = required(command.grantKey(), 160, "H7_VOUCHER_GRANT_KEY_REQUIRED");
        String sourceId = required(command.sourceId(), 96, "H7_VOUCHER_SOURCE_ID_REQUIRED");
        if (!grantKey.matches("^[A-Za-z0-9:._-]+$") || !sourceId.matches("^[A-Za-z0-9:._-]+$")) {
            throw validation("H7_VOUCHER_GRANT_REFERENCE_INVALID");
        }
        return new ValidGrant(
                command.userId(),
                required(command.voucherId(), 80, "H7_VOUCHER_ID_REQUIRED"),
                grantKey,
                sourceType,
                sourceId,
                required(command.operator(), 80, "H7_VOUCHER_OPERATOR_REQUIRED"),
                required(command.reason(), MAX_REASON_LENGTH, "H7_VOUCHER_REASON_REQUIRED"));
    }

    private String required(String value, int maxLength, String error) {
        if (!StringUtils.hasText(value)) {
            throw validation(error);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw validation(error + "_TOO_LONG");
        }
        return normalized;
    }

    private boolean sameGrant(Map<String, Object> existing, ValidGrant request) {
        if (existing == null || !StringUtils.hasText(String.valueOf(existing.get("grantId")))) {
            return false;
        }
        return Objects.equals(String.valueOf(existing.get("grantKey")), request.grantKey())
                && Objects.equals(String.valueOf(existing.get("voucherId")), request.voucherId())
                && longValue(existing.get("userId")) == request.userId()
                && Objects.equals(String.valueOf(existing.get("sourceType")), request.sourceType())
                && Objects.equals(String.valueOf(existing.get("sourceId")), request.sourceId());
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private BizException validation(String message) {
        return new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message);
    }

    private record ValidGrant(
            Long userId,
            String voucherId,
            String grantKey,
            String sourceType,
            String sourceId,
            String operator,
            String reason) {
    }
}
