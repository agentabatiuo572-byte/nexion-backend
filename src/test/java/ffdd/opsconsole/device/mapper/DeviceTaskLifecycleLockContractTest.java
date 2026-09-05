package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class DeviceTaskLifecycleLockContractTest {
    @Test
    void everyTaskWriterLocksTheTaskRowBeforeReadingMutableState() throws Exception {
        String lockSql = String.join(" ", DeviceCatalogMapper.class
                .getMethod("findTaskForUpdate", String.class)
                .getAnnotation(Select.class).value());
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/application/OpsDeviceService.java"));

        assertThat(lockSql).contains("nx_admin_device_task", "FOR UPDATE");
        assertThat(service).contains(
                "catalogRepository.findTaskForUpdate(normalized)",
                "catalogRepository.findTaskForUpdate(candidate.taskId())");
        assertThat(occurrences(service, "catalogRepository.findTaskForUpdate(normalized)")).isGreaterThanOrEqualTo(4);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
