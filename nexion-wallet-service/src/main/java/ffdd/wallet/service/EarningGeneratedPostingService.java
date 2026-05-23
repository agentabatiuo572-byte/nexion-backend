package ffdd.wallet.service;

import ffdd.common.exception.BizException;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.dto.EarningGeneratedPayload;
import ffdd.wallet.dto.PostEarningRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EarningGeneratedPostingService {
    private final WalletService walletService;

    public EarningGeneratedPostingService(WalletService walletService) {
        this.walletService = walletService;
    }

    public WalletLedger post(EarningGeneratedPayload payload) {
        validate(payload);
        PostEarningRequest request = new PostEarningRequest();
        request.setEventNo(payload.getEventNo());
        return walletService.postEarning(request);
    }

    private void validate(EarningGeneratedPayload payload) {
        if (payload == null) {
            throw new BizException("EarningGenerated payload is required");
        }
        if (!StringUtils.hasText(payload.getEventNo())) {
            throw new BizException("EarningGenerated eventNo is required");
        }
    }
}
