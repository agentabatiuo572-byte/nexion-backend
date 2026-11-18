package ffdd.opsconsole.content.application;

import static ffdd.opsconsole.content.domain.I18nCopyQuality.containsSunsetCapability;
import static ffdd.opsconsole.content.domain.I18nCopyQuality.containsUnsafeText;
import static ffdd.opsconsole.content.domain.I18nCopyQuality.hasCopyText;
import static ffdd.opsconsole.content.domain.I18nCopyQuality.placeholdersMatch;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.I18nCopyQuality;
import ffdd.opsconsole.content.domain.MessageTranslations;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningQuizQuestionRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.util.StringUtils;

/** Course content rules shared by the formal and run-scoped acceptance catalogs. */
final class LearningCourseCopy {
    static final List<String> CATEGORIES = List.of("Basics", "Earn", "Team", "Wealth", "Security");
    static final List<String> FORMATS = List.of("Article", "Video", "Hands-on");
    static final List<String> LEVELS = List.of("Beginner", "Intermediate", "Advanced");
    static final List<String> STATUSES = List.of("draft", "ready", "published", "archived");

    private LearningCourseCopy() {}

    static LearningCourseUpsertRequest complete(LearningCourseUpsertRequest request, List<String> required) {
        List<LearningQuizQuestionRequest> questions = request.quizQuestions() == null ? List.of() : request.quizQuestions();
        boolean fillZh = !required.contains("zh") && !hasCopyText(request.titleZh())
                && !hasCopyText(request.bodyZh()) && questions.stream().filter(Objects::nonNull)
                .noneMatch(q -> hasCopyText(q.questionZh()) || hasOptionText(q.optionsZh()));
        boolean fillVi = !required.contains("vi") && !hasCopyText(request.titleVi())
                && !hasCopyText(request.bodyVi()) && questions.stream().filter(Objects::nonNull)
                .noneMatch(q -> hasCopyText(q.questionVi()) || hasOptionText(q.optionsVi()));
        if (!fillZh && !fillVi) return request;

        List<LearningQuizQuestionRequest> completedQuestions = questions.stream().map(q -> q == null ? null
                : new LearningQuizQuestionRequest(q.questionId(), fillZh ? q.questionEn() : q.questionZh(), q.questionEn(),
                        fillZh ? q.optionsEn() : q.optionsZh(), q.optionsEn(), q.correctOptionIndex(),
                        fillVi ? q.questionEn() : q.questionVi(), fillVi ? q.optionsEn() : q.optionsVi(),
                        q.questionTranslations(), q.optionTranslations())).toList();
        return new LearningCourseUpsertRequest(fillZh ? request.titleEn() : request.titleZh(), request.titleEn(),
                fillZh ? request.bodyEn() : request.bodyZh(), request.bodyEn(), request.category(), request.format(),
                request.difficulty(), request.rewardNex(), request.duration(), request.publishState(), request.operator(),
                request.reason(), completedQuestions, request.passScore(), request.retryLimit(), request.completionCondition(),
                request.rewardEvent(), request.expectedRevision(), fillVi ? request.titleEn() : request.titleVi(),
                fillVi ? request.bodyEn() : request.bodyVi(), request.version(), request.titleTranslations(), request.bodyTranslations());
    }

    private static boolean hasOptionText(List<String> options) {
        return options != null && options.stream().anyMatch(I18nCopyQuality::hasCopyText);
    }

    static ApiResult<Void> validateBase(LearningCourseUpsertRequest request) {
        if (request == null || !hasCopyText(request.titleZh()) || !hasCopyText(request.titleEn())
                || !hasCopyText(request.titleVi()) || !hasCopyText(request.bodyZh())
                || !hasCopyText(request.bodyEn()) || !hasCopyText(request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_COPY_REQUIRED");
        }
        String primaryTitle = request.titleZh().trim();
        if (primaryTitle.codePointCount(0, primaryTitle.length()) > 128
                || Stream.of(request.titleEn(), request.titleVi(),
                        request.bodyZh(), request.bodyEn(), request.bodyVi()).anyMatch(value -> value.trim().length() > 1024)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_COPY_TOO_LONG");
        }
        return null;
    }

    static boolean validEnums(LearningCourseUpsertRequest request) {
        return containsIgnoreCase(CATEGORIES, request.category()) && containsIgnoreCase(FORMATS, request.format())
                && containsIgnoreCase(LEVELS, request.difficulty()) && STATUSES.contains(normalizeStatus(request.publishState()));
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        return values.stream().anyMatch(row -> row.equalsIgnoreCase(value == null ? "" : value.trim()));
    }

    static String normalizeStatus(String status) {
        String value = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "draft";
        return STATUSES.contains(value) ? value : "";
    }

    static ApiResult<Void> validateContent(LearningCourseUpsertRequest request) {
        if (containsUnsafeText(request.titleZh(), request.titleEn(), request.titleVi(), request.bodyZh(), request.bodyEn(), request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (containsSunsetCapability(request.titleZh(), request.titleEn(), request.titleVi(), request.bodyZh(), request.bodyEn(), request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "SUNSET_CAPABILITY_READONLY");
        }
        if (!I18nCopyQuality.placeholdersMatch(request.bodyEn(), request.bodyZh(), request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_PLACEHOLDERS_MISMATCH");
        }
        for (var field : List.of(Map.entry(request.titleEn(), MessageTranslations.clean(request.titleTranslations())),
                Map.entry(request.bodyEn(), MessageTranslations.clean(request.bodyTranslations())))) {
            for (var translated : field.getValue().entrySet()) {
                String value = translated.getValue();
                if (!MessageTranslations.LOCALES.containsKey(translated.getKey()) || value.length() > 1024
                        || containsUnsafeText(value) || containsSunsetCapability(value)
                        || !placeholdersMatch(field.getKey(), value)
                        || I18nCopyQuality.publishError(value, value, value) != null)
                    return ApiResult.fail(422, "LEARNING_COURSE_TRANSLATION_INVALID");
            }
        }
        return validateQuiz(request);
    }

    private static ApiResult<Void> validateQuiz(LearningCourseUpsertRequest request) {
        List<LearningQuizQuestionRequest> questions = request.quizQuestions() == null ? List.of() : request.quizQuestions();
        if (questions.isEmpty()) {
            return null;
        }
        if (request.passScore() == null || request.passScore() < 1 || request.passScore() > 100
                || request.retryLimit() == null || request.retryLimit() < 0 || request.retryLimit() > 10) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_QUIZ_INCOMPLETE");
        }
        for (LearningQuizQuestionRequest question : questions) {
            List<String> zhOptions = question == null || question.optionsZh() == null ? List.of() : question.optionsZh();
            List<String> enOptions = question == null || question.optionsEn() == null ? List.of() : question.optionsEn();
            List<String> viOptions = question == null || question.optionsVi() == null ? List.of() : question.optionsVi();
            if (question != null && (MessageTranslations.clean(question.questionTranslations()).entrySet().stream()
                    .anyMatch(entry -> !MessageTranslations.LOCALES.containsKey(entry.getKey())
                            || entry.getValue().length() > 1024 || containsUnsafeText(entry.getValue())
                            || containsSunsetCapability(entry.getValue()))
                    || question.optionTranslations() != null && question.optionTranslations().entrySet().stream()
                    .anyMatch(entry -> !MessageTranslations.LOCALES.containsKey(entry.getKey()) || entry.getValue() == null
                            || entry.getValue().size() != enOptions.size() || entry.getValue().stream()
                            .anyMatch(value -> !hasCopyText(value) || value.length() > 1024
                                    || containsUnsafeText(value) || containsSunsetCapability(value))))) {
                return ApiResult.fail(422, "LEARNING_COURSE_QUIZ_TRANSLATION_INVALID");
            }
            if (question == null || !StringUtils.hasText(question.questionId())
                    || !hasCopyText(question.questionZh()) || !hasCopyText(question.questionEn())
                    || !hasCopyText(question.questionVi())
                    || enOptions.size() < 2 || enOptions.size() != zhOptions.size() || enOptions.size() != viOptions.size()
                    || zhOptions.stream().anyMatch(value -> !hasCopyText(value))
                    || enOptions.stream().anyMatch(value -> !hasCopyText(value))
                    || viOptions.stream().anyMatch(value -> !hasCopyText(value))
                    || question.correctOptionIndex() == null || question.correctOptionIndex() < 0
                    || question.correctOptionIndex() >= enOptions.size()) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_QUIZ_INCOMPLETE");
            }
        }
        return null;
    }

    static boolean hasCompleteQuiz(LearningCourseUpsertRequest request) {
        return request != null && request.quizQuestions() != null && !request.quizQuestions().isEmpty()
                && validateQuiz(request) == null;
    }

    static ApiResult<Void> requirePublishable(LearningCourseUpsertRequest request) {
        return requirePublishable(request.titleZh(), request.titleEn(), request.titleVi(),
                request.bodyZh(), request.bodyEn(), request.bodyVi());
    }

    static ApiResult<Void> requirePublishable(String titleZh, String titleEn, String titleVi,
            String bodyZh, String bodyEn, String bodyVi) {
        if (Stream.of(titleZh, titleEn, titleVi, bodyZh, bodyEn, bodyVi).anyMatch(value -> !hasCopyText(value))) {
            return ApiResult.fail(422, "LEARNING_COURSE_COPY_REQUIRED");
        }
        String error = I18nCopyQuality.publishError(titleZh, titleEn, titleVi);
        if (error == null) error = I18nCopyQuality.publishError(bodyZh, bodyEn, bodyVi);
        return error == null ? null : ApiResult.fail(422, error);
    }

    static List<LearningQuizQuestionRequest> normalizeQuestions(List<LearningQuizQuestionRequest> questions) {
        return questions == null ? List.of() : questions.stream().map(question -> new LearningQuizQuestionRequest(
                        question.questionId().trim(), question.questionZh().trim(), question.questionEn().trim(),
                        question.optionsZh().stream().map(String::trim).toList(),
                        question.optionsEn().stream().map(String::trim).toList(), question.correctOptionIndex(),
                        question.questionVi().trim(), question.optionsVi().stream().map(String::trim).toList(),
                        MessageTranslations.clean(question.questionTranslations()), question.optionTranslations())).toList();
    }
}
