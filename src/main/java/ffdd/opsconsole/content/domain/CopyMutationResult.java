package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.api.ApiResult;

/** Serializable idempotency envelope for I1 copy mutations. */
public record CopyMutationResult(
        int code,
        String message,
        CopyContentRow data) {

    public static CopyMutationResult from(ApiResult<CopyContentRow> result) {
        return new CopyMutationResult(result.getCode(), result.getMessage(), result.getData());
    }

    public ApiResult<CopyContentRow> toApiResult() {
        return new ApiResult<>(code, message, data);
    }
}
