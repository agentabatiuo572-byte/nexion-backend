package ffdd.opsconsole.content.domain;

/** Authoritative receipt for an attempt whose POST response was not observed by the App. */
public record LearningQuizReceipt(boolean committed, String requestHash, AppLearningQuizResult result) {
}
