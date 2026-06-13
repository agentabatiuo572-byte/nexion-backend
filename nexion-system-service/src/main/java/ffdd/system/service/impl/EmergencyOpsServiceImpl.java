package ffdd.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.system.domain.EmergencySopStep;
import ffdd.system.domain.EmergencyTamperGate;
import ffdd.system.dto.EmergencySopStatusRequest;
import ffdd.system.dto.EmergencySopStepResponse;
import ffdd.system.dto.EmergencyTamperGateResponse;
import ffdd.system.dto.EmergencyTamperReviewRequest;
import ffdd.system.mapper.EmergencySopStepMapper;
import ffdd.system.mapper.EmergencyTamperGateMapper;
import ffdd.system.service.EmergencyOpsService;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class EmergencyOpsServiceImpl implements EmergencyOpsService {
    private static final int ACTIVE = 1;
    private static final Set<String> VERDICTS = Set.of("CONFIRMED", "FALSE_POSITIVE", "BANNED");
    private static final Set<String> SOP_STATUSES = Set.of("DRILLING", "DONE");
    private static final Map<String, TamperSeed> TAMPER_SEEDS = new LinkedHashMap<>();
    private static final List<SopSeed> SOP_SEEDS = List.of(
            new SopSeed("step.1", 1, "接监管点名 -> 内容定位违规文案 key"),
            new SopSeed("step.2", 2, "I2 per-channel kill 立即下架推送"),
            new SopSeed("step.3", 3, "J1 相关功能闸熔断(双人复核)"),
            new SopSeed("step.4", 4, "J2 geo-block 涉事地区"),
            new SopSeed("step.5", 5, "I5 风险披露 re-ack 强制下发"),
            new SopSeed("step.6", 6, "A2 全程留痕 -> emergency_playbook_executed"));

    static {
        TAMPER_SEEDS.put("client_version", new TamperSeed("client_version", "client 版本推进拦截", 0));
        TAMPER_SEEDS.put("ab_group", new TamperSeed("ab_group", "A/B 分组篡改尝试", 3));
        TAMPER_SEEDS.put("local_balance", new TamperSeed("local_balance", "本地余额改写拦截", 12));
        TAMPER_SEEDS.put("risk_tamper_detected", new TamperSeed("risk_tamper_detected", "风险篡改检出", 2));
    }

    private final EmergencyTamperGateMapper tamperGateMapper;
    private final EmergencySopStepMapper sopStepMapper;

    @Override
    @Transactional
    public List<EmergencyTamperGateResponse> listTamperGates() {
        Map<String, EmergencyTamperGate> rows = tamperGateMapper.selectList(new LambdaQueryWrapper<EmergencyTamperGate>()
                        .eq(EmergencyTamperGate::getIsDeleted, 0))
                .stream()
                .collect(LinkedHashMap::new, (map, row) -> map.put(row.getGateKey(), row), Map::putAll);
        for (TamperSeed seed : TAMPER_SEEDS.values()) {
            rows.computeIfAbsent(seed.gateKey(), key -> insertTamperGate(seed));
        }
        return TAMPER_SEEDS.keySet().stream()
                .map(rows::get)
                .map(this::toTamperResponse)
                .toList();
    }

    @Override
    @Transactional
    public EmergencyTamperGateResponse reviewTamperGate(String gateKey, EmergencyTamperReviewRequest request) {
        if (request == null) {
            throw new BizException("review request is required");
        }
        String normalizedGate = normalizeTamperGate(gateKey);
        String verdict = normalizeAllowed(request.getVerdict(), VERDICTS, "Invalid tamper verdict");
        String operator = requireText(request.getOperator(), "operator");
        String reason = requireText(request.getReason(), "reason");
        EmergencyTamperGate row = findTamperGate(normalizedGate);
        row.setVerdict(verdict);
        row.setReviewReason(reason);
        row.setReviewedBy(operator);
        row.setReviewedAt(LocalDateTime.now());
        row.setStatus(ACTIVE);
        tamperGateMapper.updateById(row);
        return toTamperResponse(row);
    }

    @Override
    @Transactional
    public List<EmergencySopStepResponse> listSopSteps() {
        Map<String, EmergencySopStep> rows = sopStepMapper.selectList(new LambdaQueryWrapper<EmergencySopStep>()
                        .eq(EmergencySopStep::getIsDeleted, 0))
                .stream()
                .collect(LinkedHashMap::new, (map, row) -> map.put(row.getSopId(), row), Map::putAll);
        for (SopSeed seed : SOP_SEEDS) {
            rows.computeIfAbsent(seed.sopId(), key -> insertSopStep(seed));
        }
        return SOP_SEEDS.stream()
                .map(seed -> rows.get(seed.sopId()))
                .sorted(Comparator.comparing(EmergencySopStep::getStepOrder))
                .map(this::toSopResponse)
                .toList();
    }

    @Override
    @Transactional
    public EmergencySopStepResponse updateSopStepStatus(String sopId, EmergencySopStatusRequest request) {
        if (request == null) {
            throw new BizException("SOP status request is required");
        }
        String normalizedSopId = normalizeSopId(sopId);
        String status = normalizeAllowed(request.getStatus(), SOP_STATUSES, "Invalid SOP status");
        String operator = requireText(request.getOperator(), "operator");
        String reason = requireText(request.getReason(), "reason");
        EmergencySopStep row = findSopStep(normalizedSopId);
        row.setStatus(status);
        row.setOperator(operator);
        row.setStatusReason(reason);
        row.setOperatedAt(LocalDateTime.now());
        sopStepMapper.updateById(row);
        return toSopResponse(row);
    }

    private EmergencyTamperGate findTamperGate(String gateKey) {
        EmergencyTamperGate row = tamperGateMapper.selectOne(new LambdaQueryWrapper<EmergencyTamperGate>()
                .eq(EmergencyTamperGate::getGateKey, gateKey)
                .eq(EmergencyTamperGate::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (row != null) {
            return row;
        }
        return insertTamperGate(TAMPER_SEEDS.get(gateKey));
    }

    private EmergencySopStep findSopStep(String sopId) {
        EmergencySopStep row = sopStepMapper.selectOne(new LambdaQueryWrapper<EmergencySopStep>()
                .eq(EmergencySopStep::getSopId, sopId)
                .eq(EmergencySopStep::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (row != null) {
            return row;
        }
        return insertSopStep(SOP_SEEDS.stream()
                .filter(seed -> seed.sopId().equals(sopId))
                .findFirst()
                .orElseThrow(() -> new BizException(404, "SOP step not found")));
    }

    private EmergencyTamperGate insertTamperGate(TamperSeed seed) {
        EmergencyTamperGate row = new EmergencyTamperGate();
        row.setGateKey(seed.gateKey());
        row.setGateName(seed.gateName());
        row.setEventCount24h(seed.eventCount24h());
        row.setStatus(ACTIVE);
        row.setIsDeleted(0);
        tamperGateMapper.insert(row);
        return row;
    }

    private EmergencySopStep insertSopStep(SopSeed seed) {
        EmergencySopStep row = new EmergencySopStep();
        row.setSopId(seed.sopId());
        row.setStepOrder(seed.stepOrder());
        row.setStepTitle(seed.stepTitle());
        row.setStatus("PENDING");
        row.setIsDeleted(0);
        sopStepMapper.insert(row);
        return row;
    }

    private String normalizeTamperGate(String gateKey) {
        String normalized = requireText(gateKey, "gateKey");
        if (!TAMPER_SEEDS.containsKey(normalized)) {
            throw new BizException(404, "Tamper gate not found");
        }
        return normalized;
    }

    private String normalizeSopId(String sopId) {
        String normalized = requireText(sopId, "sopId");
        boolean exists = SOP_SEEDS.stream().anyMatch(seed -> seed.sopId().equals(normalized));
        if (!exists) {
            throw new BizException(404, "SOP step not found");
        }
        return normalized;
    }

    private String normalizeAllowed(String value, Set<String> allowed, String message) {
        String normalized = requireText(value, "value").toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BizException(message);
        }
        return normalized;
    }

    private String requireText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new BizException(field + " is required");
        }
        return normalized;
    }

    private EmergencyTamperGateResponse toTamperResponse(EmergencyTamperGate row) {
        return new EmergencyTamperGateResponse(
                row.getId(),
                row.getGateKey(),
                row.getGateName(),
                row.getEventCount24h(),
                row.getVerdict(),
                row.getReviewReason(),
                row.getReviewedBy(),
                row.getReviewedAt(),
                row.getStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private EmergencySopStepResponse toSopResponse(EmergencySopStep row) {
        return new EmergencySopStepResponse(
                row.getId(),
                row.getSopId(),
                row.getStepOrder(),
                row.getStepTitle(),
                row.getStatus(),
                row.getStatusReason(),
                row.getOperator(),
                row.getOperatedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private record TamperSeed(String gateKey, String gateName, Integer eventCount24h) {
    }

    private record SopSeed(String sopId, Integer stepOrder, String stepTitle) {
    }
}
