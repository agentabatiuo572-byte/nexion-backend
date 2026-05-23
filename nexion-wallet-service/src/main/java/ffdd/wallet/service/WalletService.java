package ffdd.wallet.service;

import ffdd.common.api.PageResult;
import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.LedgerQueryRequest;
import ffdd.wallet.dto.PostEarningRequest;
import ffdd.wallet.dto.PostEarningsResponse;
import ffdd.wallet.dto.PostPendingEarningsRequest;
import ffdd.wallet.dto.PostWalletCreditRequest;

public interface WalletService {
    UserWallet getOrCreateWallet(Long userId);

    PageResult<WalletLedger> pageLedgers(LedgerQueryRequest request);

    WalletLedger postEarning(PostEarningRequest request);

    PostEarningsResponse postPendingEarnings(PostPendingEarningsRequest request);

    WalletLedger postCredit(PostWalletCreditRequest request);
}
