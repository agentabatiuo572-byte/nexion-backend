package ffdd.wallet;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("ffdd.wallet.mapper")
@EnableFeignClients(basePackages = "ffdd.wallet.client")
@EnableScheduling
@SpringBootApplication(scanBasePackages = {"ffdd.wallet", "ffdd.common"})
public class WalletServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
