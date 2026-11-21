package ffdd.opsconsole;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.config.StorageTime;
import ffdd.opsconsole.user.infrastructure.UserWeeklyRegionMapper;
import java.util.TimeZone;
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
        TimeZone.setDefault(TimeZone.getTimeZone(StorageTime.ZONE));
        System.out.println("Nexion JVM default time zone: " + TimeZone.getDefault().getID());
        SpringApplication.run(NexionOpsConsoleApplication.class, args);
    }
}
