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

    @Test
    void sandboxUnbindIsAccountEnvironmentRunAndVersionScoped() throws NoSuchMethodException {
        String sql = String.join(" ", AppPaymentMethodMapper.class
                .getDeclaredMethod("unbindScoped", Long.class, Long.class, Long.class, String.class, String.class)
                .getAnnotation(Update.class).value());

        assertThat(sql).contains("user_id=#{userId}", "id=#{methodId}", "version=#{expectedVersion}",
                        "source_environment=#{sourceEnvironment}", "run_id=#{runId}")
                .contains("status='UNBOUND'", "version=version+1");
    }

    @Test
    void insertUsesTheCardRowProviderTokenProperty() throws NoSuchMethodException {
        String sql = String.join(" ", AppPaymentMethodMapper.class
                .getDeclaredMethod("insert", AppPaymentMethodMapper.CardRow.class)
                .getAnnotation(org.apache.ibatis.annotations.Insert.class).value());

        assertThat(sql).contains("#{cardToken}")
                .doesNotContain("#{token}");
    }

    @Test
    void cardRowSelectsExposeEveryRecordDimensionByName() {
        for (java.lang.reflect.Method method : AppPaymentMethodMapper.class.getDeclaredMethods()) {
            org.apache.ibatis.annotations.Select select = method.getAnnotation(org.apache.ibatis.annotations.Select.class);
            if (select == null || !String.join(" ", select.value()).contains("FROM nx_wallet_bank_card")) continue;
            if (method.getReturnType() != AppPaymentMethodMapper.CardRow.class
                    && method.getReturnType() != java.util.List.class) continue;

            String sql = String.join(" ", select.value());
            assertThat(sql).as(method.getName())
                    .contains("user_id userId", "run_id runId", "expiry_label expiryLabel");
        }
    }

    @Test
    void insertPersistsOnlyTokenizedDisplayFactsIncludingExpiry() throws NoSuchMethodException {
        String sql = String.join(" ", AppPaymentMethodMapper.class
                .getDeclaredMethod("insert", AppPaymentMethodMapper.CardRow.class)
                .getAnnotation(org.apache.ibatis.annotations.Insert.class).value());

        assertThat(sql).contains("expiry_label", "#{expiryLabel}")
                .doesNotContain("pan", "cvv");
    }
}
