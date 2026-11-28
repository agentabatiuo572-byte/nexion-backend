package ffdd.maintenance;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import ffdd.opsconsole.finance.application.B140TestWalletResetService;
import ffdd.opsconsole.finance.application.SupportFundJournalBindingService;
import ffdd.opsconsole.finance.mapper.B140TestWalletResetMapper;
import ffdd.opsconsole.shared.config.StorageTime;
import java.util.TimeZone;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/** Explicit local operator command. Outside the deployed application's component scan. */
public final class B140TestWalletReset {
    private B140TestWalletReset() { }

    public static void main(String[] args) {
        if (args.length != 4 || !args[0].matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || !args[1].matches("[1-9][0-9]{0,17}") || !args[2].matches("ER-[0-9A-F]{32}")
                || !args[3].matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Usage: B140TestWalletReset <server-uuid> <customer-id> <entry-no> <original-receipt-sha256>");
        }
        if (!StringUtils.hasText(System.getenv("NEXION_DB_URL")) || !StringUtils.hasText(System.getenv("NEXION_DB_USERNAME"))
                || System.getenv("NEXION_DB_PASSWORD") == null) {
            throw new IllegalStateException("Explicit NEXION_DB_URL/USERNAME/PASSWORD required");
        }
        TimeZone.setDefault(TimeZone.getTimeZone(StorageTime.ZONE));
        try (var context = new SpringApplicationBuilder(ResetConfiguration.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .run("--spring.datasource.hikari.minimum-idle=0", "--spring.datasource.hikari.maximum-pool-size=1")) {
            if (!context.getEnvironment().acceptsProfiles(Profiles.of("dev"))) {
                throw new IllegalStateException("B140 wallet reset requires the dev profile");
            }
            var service = context.getBean(B140TestWalletResetService.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            var receipt = transaction.execute(status -> service.execute(args[0], Long.parseLong(args[1]), args[2], args[3]));
            System.out.printf("B140_WALLET_RESET operationId=%d operationKey=%s receiptSha256=%s%n",
                    receipt.operationId(), receipt.operationKey(), receipt.receiptSha256());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackageClasses = B140TestWalletResetMapper.class, annotationClass = Mapper.class)
    @Import({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            JacksonAutoConfiguration.class, MybatisPlusAutoConfiguration.class,
            B140TestWalletResetService.class, SupportFundJournalBindingService.class})
    static class ResetConfiguration { }
}
