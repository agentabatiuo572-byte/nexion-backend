package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.DevelopmentPayoutAddressMapper;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;

class DevelopmentPayoutAddressBootstrapTest {

    @Test
    void duplicateRowsAreIgnoredWithoutUpdatingUserData() throws Exception {
        Insert insert = DevelopmentPayoutAddressMapper.class
                .getMethod("insertIfAbsent", DevelopmentPayoutAddressMapper.DevelopmentPayoutAddress.class)
                .getAnnotation(Insert.class);

        String sql = String.join(" ", insert.value()).replaceAll("\\s+", " ").trim().toUpperCase();
        assertThat(sql).startsWith("INSERT IGNORE INTO NX_USER_PAYOUT_ADDRESS");
        assertThat(sql).doesNotContain("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void registersOnlyForDevelopmentWithoutProduction() {
        Profile profile = DevelopmentPayoutAddressBootstrap.class.getAnnotation(Profile.class);

        assertThat(profile.value()).containsExactly("dev & !prod");
        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches("dev"::equals)).isTrue();
        assertThat(expression.matches(name -> java.util.Set.of("dev", "prod").contains(name))).isFalse();
        assertThat(expression.matches("prod"::equals)).isFalse();
    }

    @Test
    void seedsThreeFormatValidAddressesIntoTheCanonicalBusinessTable() throws Exception {
        DevelopmentPayoutAddressMapper mapper = mock(DevelopmentPayoutAddressMapper.class);
        when(mapper.findDevelopmentUserId("+86", "18708173775")).thenReturn(60723152670L);
        when(mapper.insertIfAbsent(any())).thenReturn(1);
        when(mapper.insertHistory(any())).thenReturn(1);
        DevelopmentPayoutAddressBootstrap bootstrap = new DevelopmentPayoutAddressBootstrap(
                mapper, "+86", "18708173775", true);

        assertThat(bootstrap.seed()).isEqualTo(3);

        ArgumentCaptor<DevelopmentPayoutAddressMapper.DevelopmentPayoutAddress> rows =
                ArgumentCaptor.forClass(DevelopmentPayoutAddressMapper.DevelopmentPayoutAddress.class);
        verify(mapper, org.mockito.Mockito.times(3)).insertIfAbsent(rows.capture());
        verify(mapper, org.mockito.Mockito.times(3)).insertHistory(any());
        Map<String, String> addresses = rows.getAllValues().stream().collect(java.util.stream.Collectors.toMap(
                DevelopmentPayoutAddressMapper.DevelopmentPayoutAddress::network,
                DevelopmentPayoutAddressMapper.DevelopmentPayoutAddress::address));
        assertThat(addresses).containsOnlyKeys("USDT-TRC20", "USDT-BEP20", "USDT-ERC20");
        assertThat(addresses.get("USDT-TRC20")).matches("^T[1-9A-HJ-NP-Za-km-z]{33}$");
        assertThat(addresses.get("USDT-BEP20")).matches("^0x[0-9a-f]{40}$");
        assertThat(addresses.get("USDT-ERC20")).matches("^0x[0-9a-f]{40}$");
        assertThat(addresses.get("USDT-BEP20")).isNotEqualTo(addresses.get("USDT-ERC20"));
    }

    @Test
    void neverOverwritesAnExistingOrDeletedAddressRow() {
        DevelopmentPayoutAddressMapper mapper = mock(DevelopmentPayoutAddressMapper.class);
        when(mapper.findDevelopmentUserId("+86", "18708173775")).thenReturn(60723152670L);
        when(mapper.insertIfAbsent(any())).thenReturn(0);
        DevelopmentPayoutAddressBootstrap bootstrap = new DevelopmentPayoutAddressBootstrap(
                mapper, "+86", "18708173775", true);

        assertThat(bootstrap.seed()).isZero();

        verify(mapper, org.mockito.Mockito.times(3)).insertIfAbsent(any());
        verify(mapper, never()).insertHistory(any());
    }

    @Test
    void failsClosedWhenConfigurationDoesNotIdentifyTheFixedDevelopmentAccount() {
        DevelopmentPayoutAddressMapper mapper = mock(DevelopmentPayoutAddressMapper.class);
        DevelopmentPayoutAddressBootstrap bootstrap = new DevelopmentPayoutAddressBootstrap(
                mapper, "+84", "19999999999", true);

        assertThat(bootstrap.seed()).isZero();

        verify(mapper, never()).findDevelopmentUserId(any(), any());
        verify(mapper, never()).insertIfAbsent(any());
    }
}
