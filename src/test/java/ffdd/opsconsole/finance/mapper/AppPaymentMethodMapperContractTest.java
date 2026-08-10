package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.infrastructure.WalletBankCardEntity;
import ffdd.opsconsole.shared.domain.BaseEntity;
import org.junit.jupiter.api.Test;

class AppPaymentMethodMapperContractTest {
    @Test
    void mapsTheActualWalletBankCardTableThroughMybatisPlus() {
        assertThat(BaseMapper.class).isAssignableFrom(AppPaymentMethodMapper.class);
        assertThat(AppPaymentMethodMapper.class.getGenericInterfaces()[0].getTypeName())
                .contains(WalletBankCardEntity.class.getName());
        assertThat(WalletBankCardEntity.class.getAnnotation(TableName.class).value())
                .isEqualTo("nx_wallet_bank_card");
        assertThat(WalletBankCardEntity.class.getSuperclass()).isEqualTo(BaseEntity.class);
    }
}
