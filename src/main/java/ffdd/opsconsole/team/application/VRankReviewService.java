package ffdd.opsconsole.team.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.application.PendingApprovalProjection;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.mapper.VRankPerformanceMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@ApplicationService
@RequiredArgsConstructor
public class VRankReviewService {
    private static final int PAGE_SIZE = 100;
    private static final Comparator<ReviewRow> REVIEW_ORDER = Comparator
            .comparing(ReviewRow::pendingV8Proposal)
            .thenComparingLong(ReviewRow::userId);

    private final TeamCommissionRepository commissionRepository;
    private final VRankPerformanceRepository performanceRepository;
    private final VRankPerformanceMapper performanceMapper;
    private final PendingApprovalProjection pendingApprovals;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewPage list(String cursor) {
        ReviewCursor after = parseCursor(cursor);
        var ranks = commissionRepository.vRankConfigRows();
        var v8 = VRankPromotionEngine.rankByIndex(ranks, VRankPromotionEngine.V8_INDEX);
        if (v8 == null) {
            return new ReviewPage(List.of(), 0, null);
        }
        Integer requiredLevel = VRankPromotionEngine.parseRankLevel(v8.requiredDownlineRank());
        var candidates = performanceMapper.v8ReviewCandidates();
        var approvals = pendingApprovals.readV8Promotions(candidates.stream()
                .map(candidate -> Long.toString(candidate.userId())).toList());

        List<ReviewRow> matches = new ArrayList<>();
        long pendingReviewCount = 0;
        for (var candidate : candidates) {
            VRankEvaluationSnapshot snapshot = performanceRepository.computeSnapshot(candidate.userId());
            if (VRankPromotionEngine.reachableRankIndex(snapshot, ranks) != VRankPromotionEngine.V8_INDEX) {
                continue;
            }
            LocalDateTime submittedAt = approvals.get(Long.toString(candidate.userId()));
            boolean submitted = submittedAt != null;
            if (!submitted) {
                pendingReviewCount++;
            }
            if (after != null && (Boolean.compare(submitted, after.submitted()) < 0
                    || (submitted == after.submitted() && candidate.userId() <= after.userId()))) {
                continue;
            }
            int qualifiedDownlineCount = requiredLevel == null ? 0
                    : VRankPromotionEngine.cumulativeLegCount(snapshot.legCounts(), requiredLevel);
            matches.add(new ReviewRow(candidate.userId(), candidate.userCode(), candidate.nickname(), "V7",
                    snapshot.selfComputePoints(), snapshot.teamComputePoints(), snapshot.directRefs(),
                    qualifiedDownlineCount, candidate.inviterCode(), candidate.inviterNickname(), submitted, submittedAt));
        }
        matches.sort(REVIEW_ORDER);
        List<ReviewRow> items = matches.subList(0, Math.min(matches.size(), PAGE_SIZE));
        String nextCursor = null;
        if (matches.size() > PAGE_SIZE) {
            ReviewRow last = items.get(PAGE_SIZE - 1);
            nextCursor = (last.pendingV8Proposal() ? "1:" : "0:") + last.userId();
        }
        return new ReviewPage(List.copyOf(items), pendingReviewCount, nextCursor);
    }

    private static ReviewCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.matches("[01]:[1-9][0-9]*")) {
            try {
                return new ReviewCursor(cursor.charAt(0) == '1', Long.parseLong(cursor.substring(2)));
            } catch (NumberFormatException ignored) {
                // A cursor's user ID must fit the database ID type.
            }
        }
        throw new BizException(422, "F1_V8_REVIEW_CURSOR_INVALID");
    }

    private record ReviewCursor(boolean submitted, long userId) {}

    public record ReviewPage(List<ReviewRow> items, long pendingReviewCount, String nextCursor) {}

    public record ReviewRow(long userId, String userCode, String nickname, String currentRank,
                            long selfComputePoints, long teamComputePoints, int directRefs, int qualifiedDownlineCount,
                            String inviterCode, String inviterNickname, boolean pendingV8Proposal,
                            LocalDateTime pendingV8ProposalCreatedAt) {}
}
