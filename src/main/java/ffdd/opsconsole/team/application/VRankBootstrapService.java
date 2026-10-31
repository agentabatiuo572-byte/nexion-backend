package ffdd.opsconsole.team.application;

import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.dto.VRankBootstrapRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VRankBootstrapService {
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> initialize(Map<String, Object> params, AuditReplayContext context) {
        if (!A2ReplayContext.isReplaying() || A2ReplayContext.operationId() == null) {
            throw new BizException(403, "A2_CONFIRMATION_REQUIRED");
        }
        var request = VRankBootstrapRequest.from(params);
        var mutex = jdbc.queryForList("SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='F1_VRANK_BOOTSTRAP' FOR UPDATE");
        if (mutex.size() != 1) throw new BizException(503, "VRANK_BOOTSTRAP_SCHEMA_REQUIRED");
        if (!jdbc.queryForList("SELECT id FROM nx_v_rank_config FOR UPDATE").isEmpty()
                || !jdbc.queryForList("SELECT id FROM nx_v_rank_reward_rule FOR UPDATE").isEmpty()) {
            throw new BizException(409, "VRANK_BOOTSTRAP_REQUIRES_EMPTY_CONFIGURATION");
        }
        for (int index = 0; index < 9; index++) {
            var rank = request.ranks().get(index);
            jdbc.update("""
                    INSERT INTO nx_v_rank_config(rank_code,title_cn,title_en,self_compute_points,direct_refs,
                      team_compute_points,required_downline_rank,required_downline_count,unilevel_depth,
                      peer_bonus_rate,leadership_votes,sort_order,status,is_deleted)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1,0)
                    """, rank.rankCode(),rank.titleCn(),rank.titleEn(),rank.selfComputePoints(),rank.directRefs(),
                    rank.teamComputePoints(),rank.requiredDownlineRank(),rank.requiredDownlineCount(),
                    rank.unilevelDepth(),rank.peerBonusRate(),rank.leadershipVotes(),index);
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
