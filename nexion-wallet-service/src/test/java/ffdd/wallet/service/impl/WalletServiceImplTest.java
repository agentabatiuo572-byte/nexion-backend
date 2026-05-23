package ffdd.wallet.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.wallet.domain.EarningEvent;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.PostEarningRequest;
import ffdd.wallet.mapper.EarningEventMapper;
import ffdd.wallet.mapper.UserWalletMapper;
import ffdd.wallet.mapper.WalletLedgerMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WalletServiceImplTest {
    private final UserWalletMapper walletMapper = mock(UserWalletMapper.class);
    private final WalletLedgerMapper ledgerMapper = mock(WalletLedgerMapper.class);
    private final EarningEventMapper earningEventMapper = mock(EarningEventMapper.class);

    @Test
    void postEarningReturnsExistingLedgerWithoutCreditingAgain() {
        EarningEvent event = new EarningEvent();
        event.setId(11L);
        event.setEventNo("EARN-POC1-USDT");
        event.setUserId(10001L);
        event.setReceiptNo("POC-1");
        event.setAsset("USDT");
        event.setAmount(new BigDecimal("0.018"));
        event.setStatus("POSTED");

        WalletLedger existing = new WalletLedger();
        existing.setId(21L);
        existing.setBizNo("EARN-POC1-USDT");
        existing.setAsset("USDT");
        existing.setDirection("IN");
        existing.setAmount(new BigDecimal("0.018"));
        existing.setIsDeleted(0);

        when(earningEventMapper.selectOne(any())).thenReturn(event);
        when(ledgerMapper.selectOne(any())).thenReturn(existing);

        WalletServiceImpl service = new WalletServiceImpl(walletMapper, ledgerMapper, earningEventMapper);
        PostEarningRequest request = new PostEarningRequest();
        request.setEventNo("EARN-POC1-USDT");

        WalletLedger ledger = service.postEarning(request);

        assertThat(ledger.getId()).isEqualTo(21L);
        verify(ledgerMapper, never()).insert(any(WalletLedger.class));
        verify(walletMapper, never()).update(any(), any());
        verify(earningEventMapper, never()).updateById(any(EarningEvent.class));
    }
}
