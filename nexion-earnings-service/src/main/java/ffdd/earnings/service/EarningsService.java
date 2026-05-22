package ffdd.earnings.service;

import ffdd.common.api.PageResult;
import ffdd.earnings.domain.EarningEvent;
import ffdd.earnings.domain.EarningSummary;
import ffdd.earnings.dto.EarningEventQueryRequest;
import ffdd.earnings.dto.EarningSummaryQueryRequest;
import ffdd.earnings.dto.ReceiptSettleRequest;
import ffdd.earnings.dto.ReceiptSettleResponse;

public interface EarningsService {
    ReceiptSettleResponse settleReceipt(ReceiptSettleRequest request);

    PageResult<EarningEvent> pageEvents(EarningEventQueryRequest request);

    PageResult<EarningSummary> pageSummaries(EarningSummaryQueryRequest request);
}
