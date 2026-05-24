package ffdd.wallet.service;

import ffdd.common.api.PageResult;
import ffdd.wallet.domain.ExchangeOrder;
import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.dto.ApplyRiskDecisionRequest;
import ffdd.wallet.dto.CreateExchangeRequest;
import ffdd.wallet.dto.CreateWithdrawalRequest;
import ffdd.wallet.dto.FailWithdrawalRequest;
import ffdd.wallet.dto.LedgerQueryRequest;
import ffdd.wallet.dto.PostEarningRequest;
import ffdd.wallet.dto.PostEarningsResponse;
import ffdd.wallet.dto.PostPendingEarningsRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;
import ffdd.wallet.dto.PostWalletDebitRequest;
import ffdd.wallet.dto.RiskDecisionApplyResult;
import ffdd.wallet.dto.SubmitWithdrawalChainRequest;
import ffdd.wallet.dto.SucceedWithdrawalRequest;

public interface WalletService {
    UserWallet getOrCreateWallet(Long userId);

    PageResult<WalletLedger> pageLedgers(LedgerQueryRequest request);

    WalletLedger postEarning(PostEarningRequest request);

    PostEarningsResponse postPendingEarnings(PostPendingEarningsRequest request);

    WalletLedger postCredit(PostWalletCreditRequest request);

    WalletLedger postDebit(PostWalletDebitRequest request);

    WithdrawalOrder createWithdrawal(CreateWithdrawalRequest request);

    ExchangeOrder createExchange(CreateExchangeRequest request);

    RiskDecisionApplyResult applyRiskDecision(ApplyRiskDecisionRequest request);

    WithdrawalOrder submitWithdrawalChain(String withdrawalNo, SubmitWithdrawalChainRequest request);

    WithdrawalOrder succeedWithdrawal(String withdrawalNo, SucceedWithdrawalRequest request);

    WithdrawalOrder failWithdrawal(String withdrawalNo, FailWithdrawalRequest request);
}
