package ffdd.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.team.domain.CommissionEvent;
import ffdd.team.domain.CommissionRule;
import ffdd.team.domain.VRankConfig;
import ffdd.team.dto.BinaryCommissionRequest;
import ffdd.team.dto.CommissionResult;
import ffdd.team.dto.CultivationCommissionRequest;
import ffdd.team.dto.LeadershipCommissionRequest;
import ffdd.team.dto.PeerCommissionRequest;
import ffdd.team.dto.SponsorNode;
import ffdd.team.dto.UnilevelCommissionRequest;
import ffdd.team.mapper.CommissionEventMapper;
import ffdd.team.mapper.CommissionRuleMapper;
import ffdd.team.mapper.VRankConfigMapper;
import ffdd.team.service.CommissionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommissionServiceImpl implements CommissionService {
    private static final BigDecimal BINARY_MIN_WING_USD = new BigDecimal("1000");
    private static final BigDecimal BINARY_RATE = new BigDecimal("0.10");
    private static final BigDecimal BINARY_DAILY_CAP_USD = new BigDecimal("5000");
    private static final BigDecimal PEER_RATE = new BigDecimal("0.05");
    private static final BigDecimal LEADERSHIP_POOL_RATE = new BigDecimal("0.05");

    private final CommissionRuleMapper commissionRuleMapper;
    private final CommissionEventMapper commissionEventMapper;
    private final VRankConfigMapper vRankConfigMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommissionResult settleUnilevel(UnilevelCommissionRequest request) {
        List<CommissionRule> rules = activeRules("UNILEVEL");
        List<CommissionEvent> events = new ArrayList<>();
        for (SponsorNode sponsor : request.getSponsorChain()) {
            if (sponsor.getLayerNo() == null || sponsor.getLayerNo() < 1 || sponsor.getLayerNo() > 7) {
                continue;
            }
            CommissionRule rule = findLayerRule(rules, sponsor.getLayerNo());
            if (rule == null) {
                continue;
            }
            CommissionEvent event = baseEvent(sponsor.getUserId(), "UNILEVEL", request.getBuyerUserId(),
                    request.getBuyerName(), request.getOrderNo(), request.getOrderAmountUsd(), 30);
            event.setLayerNo(sponsor.getLayerNo());
            event.setAmountUsdt(scale(request.getOrderAmountUsd().multiply(rule.getUsdtRate())));
            event.setAmountNex(scale(request.getOrderAmountUsd().multiply(rule.getNexPerUsd())));
            event.setCurrency("USDT,NEX");
            event.setRemark("Unilevel L" + sponsor.getLayerNo());
            insert(events, event);
        }
        return new CommissionResult("UNILEVEL", events.size(), events);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommissionResult settleBinary(BinaryCommissionRequest request) {
        List<CommissionEvent> events = new ArrayList<>();
        BigDecimal left = safe(request.getLeftVolumeUsd());
        BigDecimal right = safe(request.getRightVolumeUsd());
        if (left.compareTo(BINARY_MIN_WING_USD) < 0 || right.compareTo(BINARY_MIN_WING_USD) < 0) {
            return new CommissionResult("BINARY", 0, events);
        }
        BigDecimal amount = left.min(right).multiply(BINARY_RATE).min(BINARY_DAILY_CAP_USD);
        CommissionEvent event = baseEvent(request.getUserId(), "BINARY", null, null, null, null, 30);
        event.setAmountUsdt(scale(amount));
        event.setAmountNex(BigDecimal.ZERO);
        event.setCurrency("USDT");
        event.setRemark("min(left,right) * 10%, daily cap 5000");
        insert(events, event);
        return new CommissionResult("BINARY", events.size(), events);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommissionResult settlePeer(PeerCommissionRequest request) {
        CommissionEvent event = baseEvent(request.getUserId(), "PEER", request.getSourceUserId(),
                request.getSourceUserName(), null, request.getSameRankVolumeUsd(), 30);
        event.setAmountUsdt(scale(safe(request.getSameRankVolumeUsd()).multiply(PEER_RATE)));
        event.setAmountNex(BigDecimal.ZERO);
        event.setCurrency("USDT");
        event.setRemark("same V rank volume * 5%");
        List<CommissionEvent> events = new ArrayList<>();
        insert(events, event);
        return new CommissionResult("PEER", events.size(), events);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommissionResult settleCultivation(CultivationCommissionRequest request) {
        CommissionRule rule = activeRules("CULTIVATION").stream()
                .filter(item -> request.getPromotedRank().equals(item.getRankCode()))
                .findFirst()
                .orElseThrow(() -> new BizException("No cultivation rule for " + request.getPromotedRank()));
        CommissionEvent event = baseEvent(request.getUserId(), "CULTIVATION", request.getPromotedUserId(),
                request.getPromotedUserName(), null, null, 0);
        event.setAmountUsdt(BigDecimal.ZERO);
        event.setAmountNex(scale(rule.getFixedNex()));
        event.setCurrency("NEX");
        event.setStatus("UNLOCKED");
        event.setUnlockAt(LocalDateTime.now());
        event.setRemark("downline promoted to " + request.getPromotedRank());
        List<CommissionEvent> events = new ArrayList<>();
        insert(events, event);
        return new CommissionResult("CULTIVATION", events.size(), events);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommissionResult settleLeadership(LeadershipCommissionRequest request) {
        VRankConfig rank = vRankConfigMapper.selectOne(new LambdaQueryWrapper<VRankConfig>()
                .eq(VRankConfig::getRankCode, request.getVRank())
                .last("limit 1"));
        if (rank == null || rank.getLeadershipVotes() == null || rank.getLeadershipVotes() <= 0) {
            return new CommissionResult("LEADERSHIP", 0, List.of());
        }
        if (request.getTotalVotes() == null || request.getTotalVotes() <= 0) {
            throw new BizException("totalVotes must be greater than 0");
        }
        BigDecimal pool = safe(request.getWeeklyPlatformVolumeUsd()).multiply(LEADERSHIP_POOL_RATE);
        BigDecimal amount = pool.multiply(BigDecimal.valueOf(rank.getLeadershipVotes()))
                .divide(BigDecimal.valueOf(request.getTotalVotes()), 6, RoundingMode.DOWN);
        CommissionEvent event = baseEvent(request.getUserId(), "LEADERSHIP", null, null, null,
                request.getWeeklyPlatformVolumeUsd(), 0);
        event.setAmountUsdt(scale(amount));
        event.setAmountNex(BigDecimal.ZERO);
        event.setCurrency("USDT");
        event.setStatus("UNLOCKED");
        event.setUnlockAt(LocalDateTime.now());
        event.setRemark("weekly pool 5% by V-rank votes");
        List<CommissionEvent> events = new ArrayList<>();
        insert(events, event);
        return new CommissionResult("LEADERSHIP", events.size(), events);
    }

    @Override
    public List<CommissionEvent> listMine(Long userId) {
        return commissionEventMapper.selectList(new LambdaQueryWrapper<CommissionEvent>()
                .eq(CommissionEvent::getUserId, userId)
                .orderByDesc(CommissionEvent::getId));
    }

    private List<CommissionRule> activeRules(String type) {
        return commissionRuleMapper.selectList(new LambdaQueryWrapper<CommissionRule>()
                .eq(CommissionRule::getCommissionType, type)
                .eq(CommissionRule::getStatus, 1)
                .orderByAsc(CommissionRule::getLayerNo)
                .orderByAsc(CommissionRule::getRankCode));
    }

    private CommissionRule findLayerRule(List<CommissionRule> rules, int layerNo) {
        return rules.stream()
                .filter(rule -> rule.getLayerNo() != null && rule.getLayerNo() == layerNo)
                .min(Comparator.comparing(CommissionRule::getId))
                .orElse(null);
    }

    private CommissionEvent baseEvent(Long userId, String type, Long sourceUserId, String sourceUserName,
                                      String orderNo, BigDecimal orderAmount, int cooldownDays) {
        CommissionEvent event = new CommissionEvent();
        event.setUserId(userId);
        event.setCommissionType(type);
        event.setSourceUserId(sourceUserId);
        event.setSourceUserName(sourceUserName);
        event.setOrderNo(orderNo);
        event.setOrderAmountUsd(orderAmount);
        event.setStatus(cooldownDays > 0 ? "COOLING" : "UNLOCKED");
        event.setUnlockAt(LocalDateTime.now().plusDays(cooldownDays));
        event.setIsDeleted(0);
        return event;
    }

    private void insert(List<CommissionEvent> events, CommissionEvent event) {
        commissionEventMapper.insert(event);
        events.add(event);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return safe(value).setScale(6, RoundingMode.DOWN);
    }
}

