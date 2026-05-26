package ffdd.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ffdd.commerce.genesis.mapper.GenesisHoldingMapper;
import ffdd.commerce.genesis.mapper.GenesisOrderMapper;
import ffdd.commerce.genesis.mapper.GenesisSeriesMapper;
import ffdd.commerce.mapper.CommerceOrderMapper;
import ffdd.commerce.mapper.PaymentCallbackEventMapper;
import ffdd.commerce.mapper.PaymentRecordMapper;
import ffdd.commerce.mapper.TradeinApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class CommerceOpsStatsServiceContextTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(CommerceOrderMapper.class, () -> mock(CommerceOrderMapper.class))
            .withBean(PaymentRecordMapper.class, () -> mock(PaymentRecordMapper.class))
            .withBean(PaymentCallbackEventMapper.class, () -> mock(PaymentCallbackEventMapper.class))
            .withBean(TradeinApplicationMapper.class, () -> mock(TradeinApplicationMapper.class))
            .withBean(GenesisSeriesMapper.class, () -> mock(GenesisSeriesMapper.class))
            .withBean(GenesisOrderMapper.class, () -> mock(GenesisOrderMapper.class))
            .withBean(GenesisHoldingMapper.class, () -> mock(GenesisHoldingMapper.class))
            .withUserConfiguration(CommerceOpsStatsServiceConfig.class);

    @Test
    void registersStatsServiceWithProductionConstructor() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(CommerceOpsStatsService.class));
    }

    @Configuration
    @Import(CommerceOpsStatsService.class)
    static class CommerceOpsStatsServiceConfig {
    }
}
