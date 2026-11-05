package ffdd.opsconsole.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import ffdd.opsconsole.shared.exception.BizException;

public record OpsDashboardExportRequest(String reason) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new BizException(422, "B0_EXPORT_REQUEST_FIELD_NOT_ALLOWED");
    }
}
