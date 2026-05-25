package ffdd.team.service;

import ffdd.common.exception.BizException;
import ffdd.team.dto.LeadershipPoolParticipant;
import ffdd.team.dto.LeadershipPoolSnapshot;
import ffdd.team.dto.TeamCommissionSettlementResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TeamAdvancedCommissionService {
    private static final String COMMISSION_PEER = "PEER";
    private static final String COMMISSION_CULTIVATION = "CULTIVATION";
    private static final String COMMISSION_LEADERSHIP = "LEADERSHIP";
    private static final String STATUS_PENDING = "PENDING";
    private static final int MAX_SETTLEMENT_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;

    public TeamAdvancedCommissionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TeamCommissionSettlementResult settlePeer(LocalDate settlementDate, int limit) {
        YearMonth period = YearMonth.from(settlementDate == null ? LocalDate.now() : settlementDate);
        String orderNo = COMMISSION_PEER + "-" + period;
        TeamCommissionSettlementResult result = baseResult(COMMISSION_PEER, period.toString());

        List<PeerCandidate> candidates = peerCandidates(normalizeLimit(limit));
        result.setScanned(candidates.size());
        for (PeerCandidate candidate : candidates) {
            try {
                if (hasCommission(COMMISSION_PEER, candidate.userId(), orderNo)) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                BigDecimal volume = positive(candidate.sameRankVolume());
                BigDecimal rate = positive(candidate.rate());
                BigDecimal amountUsdt = scale(volume.multiply(rate));
                if (amountUsdt.compareTo(BigDecimal.ZERO) <= 0) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                Long commissionId = createPeerCommission(candidate, orderNo, amountUsdt);
                result.setCreated(result.getCreated() + 1);
                result.getCommissionIds().add(commissionId);
            } catch (DuplicateKeyException ex) {
                result.setSkipped(result.getSkipped() + 1);
            } catch (RuntimeException ex) {
                result.setFailed(result.getFailed() + 1);
            }
        }
        return result;
    }

    public TeamCommissionSettlementResult settleCultivation(LocalDate fromDate, int limit) {
        LocalDate normalizedFromDate = fromDate == null ? LocalDate.now().minusDays(30) : fromDate;
        TeamCommissionSettlementResult result = baseResult(COMMISSION_CULTIVATION, normalizedFromDate.toString());

        List<CultivationCandidate> candidates = cultivationCandidates(normalizedFromDate, normalizeLimit(limit));
        result.setScanned(candidates.size());
        for (CultivationCandidate candidate : candidates) {
            try {
                if (hasCommission(COMMISSION_CULTIVATION, candidate.sponsorUserId(), candidate.orderNo())) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                if (positive(candidate.fixedNex()).compareTo(BigDecimal.ZERO) <= 0) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                Long commissionId = createCultivationCommission(candidate);
                result.setCreated(result.getCreated() + 1);
                result.getCommissionIds().add(commissionId);
            } catch (DuplicateKeyException ex) {
                result.setSkipped(result.getSkipped() + 1);
            } catch (RuntimeException ex) {
                result.setFailed(result.getFailed() + 1);
            }
        }
        return result;
    }

    public TeamCommissionSettlementResult settleLeadership(
            LocalDate weekStart,
            BigDecimal platformVolumeUsdt,
            int limit) {
        if (platformVolumeUsdt == null || platformVolumeUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("platformVolumeUsdt must be positive");
        }

        LocalDate normalizedWeekStart = normalizeWeekStart(weekStart);
        String orderNo = COMMISSION_LEADERSHIP + "-" + normalizedWeekStart;
        LeadershipRule rule = leadershipRule();
        BigDecimal sourceVolume = scale(platformVolumeUsdt);
        BigDecimal poolUsdt = scale(sourceVolume.multiply(rule.poolRate()));
        int totalVotes = totalLeadershipVotes();

        TeamCommissionSettlementResult result = baseResult(COMMISSION_LEADERSHIP, normalizedWeekStart.toString());
        result.setSourceVolumeUsdt(sourceVolume);
        result.setPoolUsdt(poolUsdt);

        List<LeadershipCandidate> candidates = leadershipCandidates(normalizeLimit(limit));
        result.setScanned(candidates.size());
        if (totalVotes <= 0 || poolUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            result.setSkipped(candidates.size());
            return result;
        }

        for (LeadershipCandidate candidate : candidates) {
            try {
                if (hasCommission(COMMISSION_LEADERSHIP, candidate.userId(), orderNo)) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                BigDecimal amountUsdt = leadershipShare(poolUsdt, candidate.votes(), totalVotes);
                if (amountUsdt.compareTo(BigDecimal.ZERO) <= 0) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                Long commissionId = createLeadershipCommission(
                        candidate,
                        orderNo,
                        sourceVolume,
                        poolUsdt,
                        amountUsdt,
                        rule.cooldownDays());
                result.setCreated(result.getCreated() + 1);
                result.getCommissionIds().add(commissionId);
            } catch (DuplicateKeyException ex) {
                result.setSkipped(result.getSkipped() + 1);
            } catch (RuntimeException ex) {
                result.setFailed(result.getFailed() + 1);
            }
        }
        return result;
    }

    public LeadershipPoolSnapshot leadershipPoolSnapshot(Long userId, BigDecimal platformVolumeUsdt) {
        LeadershipRule rule = leadershipRule();
        BigDecimal sourceVolume = positive(platformVolumeUsdt);
        BigDecimal poolUsdt = scale(sourceVolume.multiply(rule.poolRate()));
        int totalVotes = totalLeadershipVotes();
        String rankCode = userRank(userId);
        int userVotes = userLeadershipVotes(userId);
        BigDecimal estimatedShare = leadershipShare(poolUsdt, userVotes, totalVotes);
        List<LeadershipPoolParticipant> participants = leadershipCandidates(20).stream()
                .map(candidate -> new LeadershipPoolParticipant(
                        candidate.userId(),
                        candidate.rankCode(),
                        candidate.votes(),
                        leadershipShare(poolUsdt, candidate.votes(), totalVotes)))
                .toList();
        return new LeadershipPoolSnapshot(
                userId,
                rankCode,
                userVotes > 0,
                rule.poolRate(),
                sourceVolume,
                poolUsdt,
                totalVotes,
                userVotes,
                estimatedShare,
                participants);
    }

    protected List<PeerCandidate> peerCandidates(int limit) {
        return jdbcTemplate.query("""
                SELECT u.id AS user_id,
                       u.v_rank AS rank_code,
                       COALESCE(SUM(tm.volume), 0) AS same_rank_volume,
                       COALESCE(NULLIF(cfg.peer_bonus_rate, 0), rule.usdt_rate) AS peer_rate,
                       rule.cooldown_days
                  FROM nx_user u
                  JOIN nx_v_rank_config cfg
                    ON cfg.rank_code = u.v_rank
                   AND cfg.status = 1
                   AND cfg.is_deleted = 0
                  JOIN nx_commission_rule rule
                    ON rule.commission_type = 'PEER'
                   AND rule.status = 1
                   AND rule.is_deleted = 0
                  JOIN nx_team_member tm
                    ON tm.user_id = u.id
                   AND tm.v_rank = u.v_rank
                   AND tm.is_deleted = 0
                 WHERE u.is_deleted = 0
                   AND cfg.peer_bonus_rate > 0
                 GROUP BY u.id, u.v_rank, cfg.peer_bonus_rate, rule.usdt_rate, rule.cooldown_days
                HAVING same_rank_volume > 0
                 ORDER BY u.id ASC
                 LIMIT ?
                """, this::mapPeerCandidate, limit);
    }

    protected List<CultivationCandidate> cultivationCandidates(LocalDate fromDate, int limit) {
        return jdbcTemplate.query("""
                SELECT u.sponsor_user_id,
                       l.user_id AS promoted_user_id,
                       l.to_code AS promoted_rank,
                       CONCAT(COALESCE(u.nickname, CONCAT('User#', u.id)), ' -> ', l.to_code) AS source_user_name,
                       CONCAT('CULTIVATION-', l.user_id, '-', l.to_code) AS order_no,
                       rule.fixed_nex,
                       rule.cooldown_days
                  FROM nx_user_level_log l
                  JOIN nx_user u
                    ON u.id = l.user_id
                   AND u.is_deleted = 0
                   AND u.sponsor_user_id IS NOT NULL
                  JOIN nx_commission_rule rule
                    ON rule.commission_type = 'CULTIVATION'
                   AND rule.rank_code = l.to_code
                   AND rule.fixed_nex > 0
                   AND rule.status = 1
                   AND rule.is_deleted = 0
                 WHERE l.is_deleted = 0
                   AND l.level_type IN ('V', 'V_RANK')
                   AND l.created_at >= ?
                 ORDER BY l.created_at ASC, l.id ASC
                 LIMIT ?
                """, this::mapCultivationCandidate, fromDate.atStartOfDay(), limit);
    }

    protected LeadershipRule leadershipRule() {
        List<LeadershipRule> rules = jdbcTemplate.query("""
                SELECT usdt_rate, cooldown_days
                  FROM nx_commission_rule
                 WHERE commission_type = 'LEADERSHIP'
                   AND status = 1
                   AND is_deleted = 0
                 LIMIT 1
                """, (rs, rowNum) -> new LeadershipRule(
                rs.getBigDecimal("usdt_rate"),
                rs.getInt("cooldown_days")));
        if (rules.isEmpty()) {
            throw new BizException("Leadership commission rule is not configured");
        }
        return rules.get(0);
    }

    protected List<LeadershipCandidate> leadershipCandidates(int limit) {
        return jdbcTemplate.query("""
                SELECT u.id AS user_id,
                       u.v_rank AS rank_code,
                       cfg.leadership_votes
                  FROM nx_user u
                  JOIN nx_v_rank_config cfg
                    ON cfg.rank_code = u.v_rank
                   AND cfg.status = 1
                   AND cfg.is_deleted = 0
                 WHERE u.is_deleted = 0
                   AND cfg.leadership_votes > 0
                 ORDER BY cfg.leadership_votes DESC, u.id ASC
                 LIMIT ?
                """, this::mapLeadershipCandidate, limit);
    }

    protected int totalLeadershipVotes() {
        Integer votes = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(cfg.leadership_votes), 0)
                  FROM nx_user u
                  JOIN nx_v_rank_config cfg
                    ON cfg.rank_code = u.v_rank
                   AND cfg.status = 1
                   AND cfg.is_deleted = 0
                 WHERE u.is_deleted = 0
                   AND cfg.leadership_votes > 0
                """, Integer.class);
        return votes == null ? 0 : votes;
    }

    protected boolean hasCommission(String commissionType, Long userId, String orderNo) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_commission_event
                 WHERE commission_type = ?
                   AND user_id = ?
                   AND order_no = ?
                   AND layer_no = 0
                   AND is_deleted = 0
                """, Long.class, commissionType, userId, orderNo);
        return count != null && count > 0;
    }

    protected Long createPeerCommission(PeerCandidate candidate, String orderNo, BigDecimal amountUsdt) {
        LocalDateTime unlockAt = LocalDateTime.now().plusDays(candidate.cooldownDays());
        jdbcTemplate.update("""
                INSERT INTO nx_commission_event (
                  user_id, commission_type, source_user_id, source_user_name, layer_no,
                  order_no, order_amount_usd, amount_usdt, amount_nex, currency,
                  status, unlock_at, remark, created_at, updated_at, is_deleted
                ) VALUES (?, 'PEER', NULL, ?, 0, ?, ?, ?, 0, 'USDT', ?, ?, ?, NOW(), NOW(), 0)
                """,
                candidate.userId(),
                "Peer same-rank " + candidate.rankCode(),
                orderNo,
                scale(candidate.sameRankVolume()),
                amountUsdt,
                STATUS_PENDING,
                unlockAt,
                "Same-rank volume " + scale(candidate.sameRankVolume()) + " at rate " + scale(candidate.rate()));
        return findCommissionId(COMMISSION_PEER, candidate.userId(), orderNo);
    }

    protected Long createCultivationCommission(CultivationCandidate candidate) {
        LocalDateTime unlockAt = LocalDateTime.now().plusDays(candidate.cooldownDays());
        jdbcTemplate.update("""
                INSERT INTO nx_commission_event (
                  user_id, commission_type, source_user_id, source_user_name, layer_no,
                  order_no, order_amount_usd, amount_usdt, amount_nex, currency,
                  status, unlock_at, remark, created_at, updated_at, is_deleted
                ) VALUES (?, 'CULTIVATION', ?, ?, 0, ?, 0, 0, ?, 'NEX', ?, ?, ?, NOW(), NOW(), 0)
                """,
                candidate.sponsorUserId(),
                candidate.promotedUserId(),
                candidate.sourceUserName(),
                candidate.orderNo(),
                scale(candidate.fixedNex()),
                STATUS_PENDING,
                unlockAt,
                "Downline promoted to " + candidate.promotedRank());
        return findCommissionId(COMMISSION_CULTIVATION, candidate.sponsorUserId(), candidate.orderNo());
    }

    protected Long createLeadershipCommission(
            LeadershipCandidate candidate,
            String orderNo,
            BigDecimal platformVolumeUsdt,
            BigDecimal poolUsdt,
            BigDecimal amountUsdt,
            int cooldownDays) {
        LocalDateTime unlockAt = LocalDateTime.now().plusDays(cooldownDays);
        jdbcTemplate.update("""
                INSERT INTO nx_commission_event (
                  user_id, commission_type, source_user_id, source_user_name, layer_no,
                  order_no, order_amount_usd, amount_usdt, amount_nex, currency,
                  status, unlock_at, remark, created_at, updated_at, is_deleted
                ) VALUES (?, 'LEADERSHIP', NULL, ?, 0, ?, ?, ?, 0, 'USDT', ?, ?, ?, NOW(), NOW(), 0)
                """,
                candidate.userId(),
                "Leadership pool " + candidate.rankCode(),
                orderNo,
                scale(platformVolumeUsdt),
                amountUsdt,
                STATUS_PENDING,
                unlockAt,
                "Pool " + scale(poolUsdt) + " split by " + candidate.votes() + " votes");
        return findCommissionId(COMMISSION_LEADERSHIP, candidate.userId(), orderNo);
    }

    private PeerCandidate mapPeerCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new PeerCandidate(
                rs.getLong("user_id"),
                rs.getString("rank_code"),
                rs.getBigDecimal("same_rank_volume"),
                rs.getBigDecimal("peer_rate"),
                rs.getInt("cooldown_days"));
    }

    private CultivationCandidate mapCultivationCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new CultivationCandidate(
                rs.getLong("sponsor_user_id"),
                rs.getLong("promoted_user_id"),
                rs.getString("promoted_rank"),
                rs.getString("source_user_name"),
                rs.getString("order_no"),
                rs.getBigDecimal("fixed_nex"),
                rs.getInt("cooldown_days"));
    }

    private LeadershipCandidate mapLeadershipCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new LeadershipCandidate(
                rs.getLong("user_id"),
                rs.getString("rank_code"),
                rs.getInt("leadership_votes"));
    }

    protected String userRank(Long userId) {
        List<String> ranks = jdbcTemplate.query("""
                SELECT v_rank
                  FROM nx_user
                 WHERE id = ?
                   AND is_deleted = 0
                 LIMIT 1
                """, (rs, rowNum) -> rs.getString("v_rank"), userId);
        return ranks.isEmpty() ? "V0" : ranks.get(0);
    }

    protected int userLeadershipVotes(Long userId) {
        List<Integer> votes = jdbcTemplate.query("""
                SELECT COALESCE(cfg.leadership_votes, 0)
                  FROM nx_user u
                  LEFT JOIN nx_v_rank_config cfg
                    ON cfg.rank_code = u.v_rank
                   AND cfg.status = 1
                   AND cfg.is_deleted = 0
                 WHERE u.id = ?
                   AND u.is_deleted = 0
                 LIMIT 1
                """, (rs, rowNum) -> rs.getInt(1), userId);
        return votes.isEmpty() ? 0 : votes.get(0);
    }

    private Long findCommissionId(String commissionType, Long userId, String orderNo) {
        Long id = jdbcTemplate.queryForObject("""
                SELECT id
                  FROM nx_commission_event
                 WHERE commission_type = ?
                   AND user_id = ?
                   AND order_no = ?
                   AND layer_no = 0
                   AND is_deleted = 0
                 LIMIT 1
                """, Long.class, commissionType, userId, orderNo);
        if (id == null) {
            throw new BizException(commissionType + " commission event was not created");
        }
        return id;
    }

    private TeamCommissionSettlementResult baseResult(String commissionType, String period) {
        TeamCommissionSettlementResult result = new TeamCommissionSettlementResult();
        result.setCommissionType(commissionType);
        result.setPeriod(period);
        return result;
    }

    private LocalDate normalizeWeekStart(LocalDate weekStart) {
        return (weekStart == null ? LocalDate.now() : weekStart)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 100;
        }
        return Math.min(limit, MAX_SETTLEMENT_LIMIT);
    }

    private BigDecimal leadershipShare(BigDecimal poolUsdt, int votes, int totalVotes) {
        if (poolUsdt == null || votes <= 0 || totalVotes <= 0) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return poolUsdt
                .multiply(BigDecimal.valueOf(votes))
                .divide(BigDecimal.valueOf(totalVotes), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal positive(BigDecimal value) {
        BigDecimal scaled = scale(value);
        return scaled.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP) : scaled;
    }

    protected record PeerCandidate(
            Long userId,
            String rankCode,
            BigDecimal sameRankVolume,
            BigDecimal rate,
            int cooldownDays) {
    }

    protected record CultivationCandidate(
            Long sponsorUserId,
            Long promotedUserId,
            String promotedRank,
            String sourceUserName,
            String orderNo,
            BigDecimal fixedNex,
            int cooldownDays) {
    }

    protected record LeadershipRule(BigDecimal poolRate, int cooldownDays) {
    }

    protected record LeadershipCandidate(Long userId, String rankCode, int votes) {
    }
}
