package ffdd.opsconsole;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.user.infrastructure.UserWeeklyRegionMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("ffdd.opsconsole.**.mapper")
@MapperScan(basePackages = "ffdd.opsconsole.finance.hdpay", markerInterface = BaseMapper.class)
@MapperScan(basePackageClasses = UserWeeklyRegionMapper.class, annotationClass = Mapper.class)
public class NexionOpsConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(NexionOpsConsoleApplication.class, args);
    }
}
