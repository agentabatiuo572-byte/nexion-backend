package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.boundary.AdminSearchHit;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.facade.ContentSearchFacade;
import ffdd.opsconsole.finance.facade.FinanceSearchFacade;
import ffdd.opsconsole.platform.dto.AdminGlobalSearchResult;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.user.facade.UserSearchFacade;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsGlobalSearchService {
    private static final Map<String, Integer> KIND_ORDER = Map.of(
            "user", 0,
            "ticket", 1,
            "conversation", 2,
            "withdrawal", 3);

    private final UserSearchFacade userSearchFacade;
    private final ContentSearchFacade contentSearchFacade;
    private final FinanceSearchFacade financeSearchFacade;

    public ApiResult<List<AdminGlobalSearchResult>> search(String keyword, Integer limit) {
        String q = keyword == null ? "" : keyword.trim();
        if (!StringUtils.hasText(q)) {
            return ApiResult.ok(List.of());
        }
        int finalLimit = normalizeLimit(limit);
        int perDomainLimit = Math.max(3, Math.min(finalLimit, 6));
        List<AdminSearchHit> hits = new ArrayList<>();
        hits.addAll(userSearchFacade.searchAdminUsers(q, perDomainLimit));
        hits.addAll(contentSearchFacade.searchSupportTickets(q, perDomainLimit));
        hits.addAll(contentSearchFacade.searchConversations(q, perDomainLimit));
        hits.addAll(financeSearchFacade.searchWithdrawals(q, perDomainLimit));

        Map<String, AdminSearchHit> deduped = new LinkedHashMap<>();
        hits.stream()
                .filter(this::validHit)
                .sorted(Comparator.comparingInt(AdminSearchHit::score)
                        .thenComparingInt(hit -> KIND_ORDER.getOrDefault(hit.kind().toLowerCase(Locale.ROOT), 99))
                        .thenComparing(AdminSearchHit::title))
                .forEach(hit -> deduped.putIfAbsent(hit.kind() + ":" + hit.id(), hit));
        return ApiResult.ok(deduped.values().stream()
                .limit(finalLimit)
                .map(hit -> new AdminGlobalSearchResult(hit.kind(), hit.id(), hit.title(), hit.subtitle(), hit.href()))
                .toList());
    }

    private boolean validHit(AdminSearchHit hit) {
        return hit != null
                && StringUtils.hasText(hit.kind())
                && StringUtils.hasText(hit.id())
                && StringUtils.hasText(hit.title())
                && StringUtils.hasText(hit.href());
    }

    private int normalizeLimit(Integer limit) {
        int value = limit == null ? 8 : limit;
        return Math.max(1, Math.min(value, 20));
    }
}
