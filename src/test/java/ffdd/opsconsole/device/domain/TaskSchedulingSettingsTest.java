package ffdd.opsconsole.device.domain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskSchedulingSettingsTest {
    @Test void sixLegacyDurationsAreUnchangedAndOnlyBgeGetsKnownRuntimeDefault() {
        Map.of("IG",18,"VG",900,"LL",12,"FT",1800,"EM",5,"SP",30).forEach((kind,seconds) -> {
            var settings=TaskSchedulingSettings.defaults(kind,"other");
            assertThat(settings.selectionWeight()).isEqualTo(1);
            assertThat(settings.expectedDurationMinSec()).isEqualTo(seconds);
            assertThat(settings.expectedDurationMaxSec()).isEqualTo(seconds);
            assertThat(settings.runtimeKey()).isEmpty();
        });
        assertThat(TaskSchedulingSettings.defaults("EM","BGE-M3").runtimeKey()).isEqualTo("test-bge-m3-v1");
    }

    @Test void policyBoundsRejectInvalidWeightOrderTimeoutAndUtf8ObjectConfiguration() {
        assertThat(new TaskSchedulingSettings(1,5,5,5,"test-bge-m3-v1","test-bge-m3-v1"," {\"texts\":[\"hello\"]} ").workloadConfigJson()).isEqualTo("{\"texts\":[\"hello\"]}");
        for (int weight : new int[]{0,1000001}) assertThatThrownBy(() -> new TaskSchedulingSettings(weight,5,5,5,"","","{}"));
        assertThatThrownBy(() -> new TaskSchedulingSettings(1,6,5,10,"","","{}"));
        assertThatThrownBy(() -> new TaskSchedulingSettings(1,5,86401,86401,"","","{}"));
        assertThatThrownBy(() -> new TaskSchedulingSettings(1,5,8,7,"","","{}"));
        assertThatThrownBy(() -> new TaskSchedulingSettings(1,5,8,172801,"","","{}"));
        for (String input : new String[]{"[]","null","{} {}","{\"a\":1,\"a\":2}","{\"text\":\""+"中".repeat(6000)+"\"}"})
            assertThatThrownBy(() -> new TaskSchedulingSettings(1,5,5,5,"","",input)).hasMessage("TASK_WORKLOAD_CONFIG_INVALID");
    }

    @Test void jsonSchedulingFieldsAreIntegersRatherThanSilentlyTruncatedDecimals() throws Exception {
        var mapper=new ObjectMapper();
        for (String field : new String[]{"selectionWeight","expectedDurationMinSec","expectedDurationMaxSec","executionTimeoutSec"}) {
            for (String value : new String[]{"1.5","\"1\"","true","2147483648"})
                assertThatThrownBy(() -> mapper.readValue("{\""+field+"\":"+value+"}",DeviceTaskUpsertRequest.class));
            assertThat(mapper.readValue("{\""+field+"\":1}",DeviceTaskUpsertRequest.class).hasSchedulingSettings()).isTrue();
        }
        var original=new TaskSchedulingSettings(2,5,8,30,"runtime","validator","{}");
        assertThat(original.merge(mapper.readValue("{}",DeviceTaskUpsertRequest.class))).isEqualTo(original);
    }
}
