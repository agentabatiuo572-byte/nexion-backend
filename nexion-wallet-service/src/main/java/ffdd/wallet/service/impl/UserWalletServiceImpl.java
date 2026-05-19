package ffdd.wallet.service.impl;

import ffdd.wallet.domain.UserWallet;
import ffdd.wallet.service.UserWalletService;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class UserWalletServiceImpl implements UserWalletService {
    @Override
    public UserWallet summary() {
        UserWallet wallet = new UserWallet();
        wallet.setId(1L);
        wallet.setUserId(10001L);
        wallet.setUsdtAvailable(new BigDecimal("128.64"));
        wallet.setNexAvailable(new BigDecimal("8420.00"));
        wallet.setPendingWithdraw(new BigDecimal("20.00"));
        wallet.setLifetimeEarned(new BigDecimal("612.44"));
        return wallet;
    }
}

