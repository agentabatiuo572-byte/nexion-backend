package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.finance.infrastructure.WalletBankCardEntity;
import ffdd.opsconsole.shared.domain.BaseEntity;
import org.apache.ibatis.annotations.Update;
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

    @Test
    void clearDefaultOnlyVersionsExistingDefaultsAndKeepsCasTargetUntouched() throws NoSuchMethodException {
        String sql = String.join(" ", AppPaymentMethodMapper.class
                .getDeclaredMethod("clearDefault", Long.class, String.class)
                .getAnnotation(Update.class).value());

        assertThat(sql).contains("user_id=#{userId}", "source_environment=#{sourceEnvironment}", "is_default=1")
                .doesNotContain("is_default=0 AND");
    }

    @Test
    void scopedAppQueriesRequireTheServerOwnedRunDimension() throws NoSuchMethodException {
        String sql = String.join(" ", AppPaymentMethodMapper.class
                .getDeclaredMethod("findActiveByTokenScoped", Long.class, String.class, String.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());

        assertThat(sql).contains("source_environment=#{sourceEnvironment}", "run_id=#{runId}");
        assertThat(AppPaymentMethodMapper.CardRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("runId");
    }
}
