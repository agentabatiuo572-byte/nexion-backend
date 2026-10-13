package ffdd.opsconsole.device.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MybatisDeviceOpsRepositoryPaginationTest {
    private final DeviceOpsMapper mapper = mock(DeviceOpsMapper.class);
    private final MybatisDeviceOpsRepository repository = new MybatisDeviceOpsRepository(
            mapper, mock(OpsReadTimeSeedPolicy.class));

    @ParameterizedTest
    @CsvSource(value = {
            "9223372036854775807,NULL", "922337203685477582,20", "461168601842738792,NULL",
            "9223372036854775807,101"
    }, nullValues = "NULL")
    void rejectsUnrepresentableProductBeforeCountOrRows(long page, Long size) {
        assertThatThrownBy(() -> repository.pageDevices(query(page, size)))
                .isInstanceOf(BizException.class)
                .hasMessage("E5_PAGE_NUM_INVALID")
                .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo(422));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "NULL,NULL,1,20,0", "0,0,1,1,0", "-1,-1,1,1,0", "2,101,2,100,100",
            "2,1,2,1,1", "461168601842738791,NULL,461168601842738791,20,9223372036854775800",
            "9223372036854775807,1,9223372036854775807,1,9223372036854775806",
            "1317624576693539402,7,1317624576693539402,7,9223372036854775807"
    }, nullValues = "NULL")
    void preservesNormalizationAndRepresentableLongBoundaries(Long page, Long size, long effectivePage,
            long effectiveSize, long offset) {
        var result = repository.pageDevices(query(page, size));

        assertThat(result.getPageNum()).isEqualTo(effectivePage);
        assertThat(result.getPageSize()).isEqualTo(effectiveSize);
        verify(mapper).countDevices(null, null, null, null, null, null);
        verify(mapper).pageDevices(null, null, null, null, null, null, effectiveSize, offset);
    }

    private DeviceOpsQueryRequest query(Long page, Long size) {
        return new DeviceOpsQueryRequest(null, null, null, page, size, null, null, null);
    }
}
