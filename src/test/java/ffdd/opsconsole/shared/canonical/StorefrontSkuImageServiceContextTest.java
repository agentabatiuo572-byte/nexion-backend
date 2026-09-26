package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.shared.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class StorefrontSkuImageServiceContextTest {
    @Test
    void springCreatesImageServiceWithItsProductionDependencies() {
        new ApplicationContextRunner()
                .withUserConfiguration(ImageServiceConfiguration.class)
                .withBean(AppTradeinMapper.class, () -> mock(AppTradeinMapper.class))
                .withBean(ObjectStorageService.class, () -> mock(ObjectStorageService.class))
                .withBean(StorageProperties.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StorefrontSkuImageService.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(StorefrontSkuImageService.class)
    static class ImageServiceConfiguration {
    }
}
