package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.common.boundary.AdminSearchHit;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.finance.facade.FinanceSearchFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FinanceSearchFacadeAdapter implements FinanceSearchFacade {
    private final OpsFinanceService financeService;

    @Override
    public List<AdminSearchHit> searchWithdrawals(String keyword, int limit) {
        String q = trim(keyword);
        if (!StringUtils.hasText(q)) {
            return List.of();
        }
        ApiResult<PageResult<WithdrawalOrderView>> result = financeService.withdrawals(
                new WithdrawalQueryRequest(null, null, q, 1, limit));
        PageResult<WithdrawalOrderView> page = result == null ? null : result.getData();
        if (result == null || result.getCode() != 0 || page == null || page.getRecords() == null) {
            return List.of();
        }
        return page.getRecords().stream()
                .map(withdrawal -> new AdminSearchHit(
                        "withdrawal",
                        withdrawal.withdrawalNo(),
                        withdrawal.withdrawalNo() + " · " + text(withdrawal.asset(), "USDT") + " " + withdrawal.amount(),
                        join(
                                StringUtils.hasText(withdrawal.userNo()) ? withdrawal.userNo() : "用户 " + withdrawal.userId(),
                                withdrawal.nickname(),
                                withdrawal.status(),
                                withdrawal.chain(),
                                withdrawal.riskScore() == null ? null : "风险 " + withdrawal.riskScore()),
                        "/finance/withdrawals?withdrawal=" + withdrawal.withdrawalNo(),
                        exactScore(q, withdrawal.withdrawalNo(), withdrawal.userNo(), withdrawal.nickname())))
                .toList();
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String join(String... values) {
        return java.util.Arrays.stream(values)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
    }

    private int exactScore(String keyword, Object... values) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        for (Object value : values) {
            if (needle.equals(trim(value).toLowerCase(Locale.ROOT))) {
                return 0;
            }
        }
        return 1;
    }

    private String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
