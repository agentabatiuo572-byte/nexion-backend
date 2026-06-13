package ffdd.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.wallet.domain.WalletAssetAdjustment;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.CreateAssetAdjustmentRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
import ffdd.wallet.dto.PostWalletDebitRequest;
import ffdd.wallet.dto.ReviewAssetAdjustmentRequest;
import ffdd.wallet.mapper.WalletAssetAdjustmentMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WalletAssetAdjustmentServiceTest {
    private final WalletAssetAdjustmentMapper adjustmentMapper = mock(WalletAssetAdjustmentMapper.class);
    private final WalletService walletService = mock(WalletService.class);
    private final WalletAssetAdjustmentService service = new WalletAssetAdjustmentService(adjustmentMapper, walletService);

    @Test
    void createStoresPendingAdjustmentWithServerCanonicalFields() {
        CreateAssetAdjustmentRequest request = createRequest("USDT", "credit", "12.500000");

        WalletAssetAdjustment row = service.create(request);

        assertThat(row.getAdjustmentNo()).startsWith("ADJ-");
        assertThat(row.getAsset()).isEqualTo("USDT");
        assertThat(row.getDirection()).isEqualTo("CREDIT");
        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getMaker()).isEqualTo("finance-li");
        verify(adjustmentMapper).insert(any(WalletAssetAdjustment.class));
    }

    @Test
    void reviewApprovedPostsCreditLedgerAndBackfillsLedgerId() {
        WalletAssetAdjustment pending = pending("ADJ-10001", "USDT", "CREDIT", "12.500000");
        WalletLedger ledger = new WalletLedger();
        ledger.setId(88L);
        when(adjustmentMapper.selectOne(any())).thenReturn(pending);
        when(walletService.postCredit(any(PostWalletCreditRequest.class))).thenReturn(ledger);

        WalletAssetAdjustment result = service.review("ADJ-10001", reviewRequest("APPROVED", "risk-chen"));

        assertThat(result.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getLedgerId()).isEqualTo(88L);
        verify(walletService).postCredit(any(PostWalletCreditRequest.class));
        verify(walletService, never()).postDebit(any(PostWalletDebitRequest.class));
        verify(adjustmentMapper).updateById(pending);
    }

    @Test
    void reviewApprovedPostsDebitLedgerForDebitDirection() {
        WalletAssetAdjustment pending = pending("ADJ-10002", "NEX", "DEBIT", "30.000000");
        WalletLedger ledger = new WalletLedger();
        ledger.setId(89L);
        when(adjustmentMapper.selectOne(any())).thenReturn(pending);
        when(walletService.postDebit(any(PostWalletDebitRequest.class))).thenReturn(ledger);

        WalletAssetAdjustment result = service.review("ADJ-10002", reviewRequest("APPROVED", "risk-chen"));

        assertThat(result.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getLedgerId()).isEqualTo(89L);
        verify(walletService).postDebit(any(PostWalletDebitRequest.class));
    }

    @Test
    void reviewRejectedDoesNotPostLedger() {
        WalletAssetAdjustment pending = pending("ADJ-10003", "USDT", "CREDIT", "12.500000");
        when(adjustmentMapper.selectOne(any())).thenReturn(pending);

        WalletAssetAdjustment result = service.review("ADJ-10003", reviewRequest("REJECTED", "risk-chen"));

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.getLedgerId()).isNull();
        verify(walletService, never()).postCredit(any(PostWalletCreditRequest.class));
        verify(walletService, never()).postDebit(any(PostWalletDebitRequest.class));
    }

    @Test
    void reviewRejectsMakerSelfReview() {
        WalletAssetAdjustment pending = pending("ADJ-10004", "USDT", "CREDIT", "12.500000");
        when(adjustmentMapper.selectOne(any())).thenReturn(pending);

        assertThatThrownBy(() -> service.review("ADJ-10004", reviewRequest("APPROVED", "finance-li")))
                .isInstanceOf(BizException.class)
                .hasMessage("Checker cannot be the maker");
    }

    private CreateAssetAdjustmentRequest createRequest(String asset, String direction, String amount) {
        CreateAssetAdjustmentRequest request = new CreateAssetAdjustmentRequest();
        request.setUserId(10001L);
        request.setAsset(asset);
        request.setDirection(direction);
        request.setAmount(new BigDecimal(amount));
        request.setReasonCode("CORRECTION");
        request.setReason("support correction");
        request.setMaker("finance-li");
        return request;
    }

    private ReviewAssetAdjustmentRequest reviewRequest(String decision, String checker) {
        ReviewAssetAdjustmentRequest request = new ReviewAssetAdjustmentRequest();
        request.setDecision(decision);
        request.setChecker(checker);
        request.setReviewReason("maker-checker approved");
        return request;
    }

    private WalletAssetAdjustment pending(String adjustmentNo, String asset, String direction, String amount) {
        WalletAssetAdjustment row = new WalletAssetAdjustment();
        row.setAdjustmentNo(adjustmentNo);
        row.setUserId(10001L);
        row.setAsset(asset);
        row.setDirection(direction);
        row.setAmount(new BigDecimal(amount));
        row.setReasonCode("CORRECTION");
        row.setReason("support correction");
        row.setMaker("finance-li");
        row.setStatus("PENDING");
        row.setIsDeleted(0);
        return row;
    }
}
