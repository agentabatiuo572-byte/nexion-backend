package ffdd.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.wallet.domain.UserWallet;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Select;

public interface UserWalletMapper extends BaseMapper<UserWallet> {
    @Select("SELECT COALESCE(SUM(usdt_available), 0) FROM nx_user_wallet WHERE is_deleted = 0")
    BigDecimal sumUsdtAvailable();

    @Select("SELECT COALESCE(SUM(nex_available), 0) FROM nx_user_wallet WHERE is_deleted = 0")
    BigDecimal sumNexAvailable();

    @Select("SELECT COALESCE(SUM(pending_withdraw), 0) FROM nx_user_wallet WHERE is_deleted = 0")
    BigDecimal sumPendingWithdraw();
}
