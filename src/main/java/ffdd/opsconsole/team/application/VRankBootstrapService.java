package ffdd.opsconsole.team.application;

import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.dto.VRankBootstrapRequest;
import ffdd.opsconsole.team.mapper.VRankBootstrapMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VRankBootstrapService {
    private final VRankBootstrapMapper mapper;
    private final AuditLogService audit;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> initialize(Map<String, Object> params, AuditReplayContext context) {
        if (!A2ReplayContext.isReplaying() || A2ReplayContext.operationId() == null) {
            throw new BizException(403, "A2_CONFIRMATION_REQUIRED");
        }
        var request = VRankBootstrapRequest.from(params);
        var mutex = mapper.lockBootstrapMutex();
        if (mutex.size() != 1) throw new BizException(503, "VRANK_BOOTSTRAP_SCHEMA_REQUIRED");
        if (!mapper.lockAllRankIds().isEmpty() || !mapper.lockAllRewardIds().isEmpty()) {
            throw new BizException(409, "VRANK_BOOTSTRAP_REQUIRES_EMPTY_CONFIGURATION");
        }
        for (int index = 0; index < 9; index++) {
            mapper.insertRank(request.ranks().get(index), index);
        }
        audit.recordRequired(AuditLogWriteRequest.builder().action("F_TEAM_VRANK_BOOTSTRAPPED")
                .resourceType("TEAM_POLICY").resourceId("vrank_bootstrap/current")
                .actorUsername(context.operator()).riskLevel("HIGH")
                .detail(Map.of("ranks",request.ranks(),"reason",context.reason(),
                        "idempotencyKey",context.idempotencyKey(),"operationId",A2ReplayContext.operationId())).build());
        return Map.of("initialized", true, "rankCodes", request.ranks().stream().map(VRankBootstrapRequest.Rank::rankCode).toList(),
                "source", "nx_v_rank_config", "rewardsConfigured", false);
    }
}
