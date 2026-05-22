package ffdd.compute;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@MapperScan("ffdd.compute.mapper")
@EnableFeignClients(basePackages = "ffdd.compute.client")
@SpringBootApplication(scanBasePackages = {"ffdd.compute", "ffdd.common"})
public class ComputeServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComputeServiceApplication.class, args);
    }
}
