package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.api.ApiResult;

/** Serializable idempotency envelope for I1 copy-position mutations. */
public record CopyPositionMutationResult(int code, String message, CopyPositionView data) {
    public static CopyPositionMutationResult from(ApiResult<CopyPositionView> result) {
        return new CopyPositionMutationResult(result.getCode(), result.getMessage(), result.getData());
    }

    public ApiResult<CopyPositionView> toApiResult() {
        return new ApiResult<>(code, message, data);
    }
}
