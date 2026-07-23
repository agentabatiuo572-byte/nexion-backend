package ffdd.opsconsole.team.infrastructure;

import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.mapper.VRankPerformanceMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 业绩聚合层 MyBatis 实现:聚合 4 个查询为 {@link VRankEvaluationSnapshot}。
 *
 * <p>{@link #computeSnapshot(Long)} 串行调用 4 个 mapper 方法,组装为不可变快照返回。
 * 性能可接受(用户级聚合,4 个索引查询);若未来需做批量评估,可改为单条 JOIN 查询。
 */
@Repository
@RequiredArgsConstructor
public class MybatisVRankPerformanceRepository implements VRankPerformanceRepository {

    private final VRankPerformanceMapper mapper;

    @Override
    public VRankEvaluationSnapshot computeSnapshot(Long userId) {
        if (userId == null) {
            return VRankEvaluationSnapshot.empty();
        }
        BigDecimal selfBuy = nullSafe(mapper.selfBuyUSD(userId));
        BigDecimal teamVolume = nullSafe(mapper.teamVolumeUSD(userId));
        BigDecimal v1Threshold = nullSafe(mapper.v1SelfBuyThreshold());
        int directRefs = mapper.directRefsCount(userId, v1Threshold);
        Map<Integer, Integer> legCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.legCountsByLevel(userId)) {
            Integer level = integerOf(row.get("vLevel"));
            Integer count = integerOf(row.get("memberCount"));
            if (level != null && count != null) {
                legCounts.merge(level, count, Integer::sum);
            }
        }
        return new VRankEvaluationSnapshot(selfBuy, teamVolume, directRefs, legCounts);
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Integer integerOf(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
