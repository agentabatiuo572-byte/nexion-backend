package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.api.ApiResult;

/** Serializable idempotency envelope for I1 version-catalog mutations. */
public record CopyVersionOptionMutationResult(int code, String message, CopyVersionOptionView data) {
    public static CopyVersionOptionMutationResult from(ApiResult<CopyVersionOptionView> result) {
        return new CopyVersionOptionMutationResult(result.getCode(), result.getMessage(), result.getData());
    }

    public ApiResult<CopyVersionOptionView> toApiResult() {
        return new ApiResult<>(code, message, data);
    }
}
