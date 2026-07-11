package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.api.ApiResult;

/** Serializable idempotency envelope for I1 experiment mutations. */
public record CopyExperimentMutationResult(int code, String message, CopyExperimentRow data) {
    public static CopyExperimentMutationResult from(ApiResult<CopyExperimentRow> result) {
        return new CopyExperimentMutationResult(result.getCode(), result.getMessage(), result.getData());
    }

    public ApiResult<CopyExperimentRow> toApiResult() {
        return new ApiResult<>(code, message, data);
    }
}
