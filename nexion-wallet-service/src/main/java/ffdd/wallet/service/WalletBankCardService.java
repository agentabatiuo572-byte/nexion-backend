package ffdd.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.common.exception.BizException;
import ffdd.wallet.domain.WalletBankCard;
import ffdd.wallet.dto.BankCardQueryRequest;
import ffdd.wallet.dto.CreateBankCardRequest;
import ffdd.wallet.mapper.WalletBankCardMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class WalletBankCardService {
    private final WalletBankCardMapper cardMapper;

    public WalletBankCardService(WalletBankCardMapper cardMapper) {
        this.cardMapper = cardMapper;
    }

    public List<WalletBankCard> list(BankCardQueryRequest request) {
        return cardMapper.selectList(new LambdaQueryWrapper<WalletBankCard>()
                .eq(WalletBankCard::getIsDeleted, 0)
                .eq(request.getUserId() != null, WalletBankCard::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), WalletBankCard::getStatus, request.getStatus())
                .orderByDesc(WalletBankCard::getIsDefault)
                .orderByDesc(WalletBankCard::getCreatedAt));
    }

    @Transactional(rollbackFor = Exception.class)
    public WalletBankCard create(CreateBankCardRequest request) {
        if (request.getUserId() == null || request.getUserId() <= 0) {
            throw new BizException("User id is required for bank card binding");
        }
        if (Integer.valueOf(1).equals(request.getIsDefault())) {
            clearDefault(request.getUserId());
        }
        WalletBankCard card = new WalletBankCard();
        card.setUserId(request.getUserId());
        card.setCardToken("CARD-" + UUID.randomUUID());
        card.setCardholderName(request.getCardholderName());
        card.setBrand(StringUtils.hasText(request.getBrand()) ? request.getBrand().toUpperCase() : inferBrand(request.getCardNumber()));
        card.setLast4(last4(request.getCardNumber()));
        card.setCountryCode(StringUtils.hasText(request.getCountryCode()) ? request.getCountryCode().toUpperCase() : "US");
        card.setStatus("ACTIVE");
        card.setIsDefault(request.getIsDefault() == null ? 0 : request.getIsDefault());
        card.setIsDeleted(0);
        cardMapper.insert(card);
        return card;
    }

    @Transactional(rollbackFor = Exception.class)
    public WalletBankCard setDefault(Long id) {
        return setDefault(id, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public WalletBankCard setDefault(Long id, Long ownerUserId) {
        WalletBankCard card = requireCard(id);
        assertOwned(card, ownerUserId);
        clearDefault(card.getUserId());
        WalletBankCard patch = new WalletBankCard();
        patch.setId(id);
        patch.setIsDefault(1);
        cardMapper.updateById(patch);
        card.setIsDefault(1);
        return card;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        delete(id, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long ownerUserId) {
        WalletBankCard card = requireCard(id);
        assertOwned(card, ownerUserId);
        WalletBankCard patch = new WalletBankCard();
        patch.setId(id);
        patch.setIsDeleted(1);
        patch.setStatus("DELETED");
        patch.setIsDefault(0);
        cardMapper.updateById(patch);
    }

    private void clearDefault(Long userId) {
        cardMapper.selectList(new LambdaQueryWrapper<WalletBankCard>()
                        .eq(WalletBankCard::getUserId, userId)
                        .eq(WalletBankCard::getIsDeleted, 0)
                        .eq(WalletBankCard::getIsDefault, 1))
                .forEach(card -> {
                    WalletBankCard patch = new WalletBankCard();
                    patch.setId(card.getId());
                    patch.setIsDefault(0);
                    cardMapper.updateById(patch);
                });
    }

    private WalletBankCard requireCard(Long id) {
        WalletBankCard card = cardMapper.selectOne(new LambdaQueryWrapper<WalletBankCard>()
                .eq(WalletBankCard::getId, id)
                .eq(WalletBankCard::getIsDeleted, 0));
        if (card == null) {
            throw new IllegalArgumentException("Bank card not found");
        }
        return card;
    }

    private void assertOwned(WalletBankCard card, Long ownerUserId) {
        if (ownerUserId != null && !ownerUserId.equals(card.getUserId())) {
            throw new BizException("Bank card does not belong to authenticated user");
        }
    }

    private static String last4(String cardNumber) {
        String digits = cardNumber.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    private static String inferBrand(String cardNumber) {
        String digits = cardNumber.replaceAll("\\D", "");
        if (digits.startsWith("4")) return "VISA";
        if (digits.startsWith("5")) return "MASTERCARD";
        return "CARD";
    }
}
