import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.cwd();

const service = readFileSync(
  join(root, "nexion-commerce-service/src/main/java/ffdd/commerce/service/ProductContentService.java"),
  "utf8",
);
const test = readFileSync(
  join(root, "nexion-commerce-service/src/test/java/ffdd/commerce/service/ProductContentServiceTest.java"),
  "utf8",
);
const seed = readFileSync(join(root, "scripts/seed.sql"), "utf8");
const uniappOrderDetail = readFileSync(
  join(root, "../nexion-frontend-uniapp/src/pages/store/orders/detail/index.vue"),
  "utf8",
);

const checks = [
  {
    name: "system seed exposes public product review limits",
    pass:
      seed.includes("commerce.review.media_max_count") &&
      seed.includes("commerce.review.title_max_length") &&
      seed.includes("commerce.review.content_min_length") &&
      seed.includes("commerce.review.content_max_length"),
  },
  {
    name: "uniapp reads public review limits from commerce config",
    pass:
      uniappOrderDetail.includes("review.media_max_count") &&
      uniappOrderDetail.includes("review.video_max_duration_sec") &&
      uniappOrderDetail.includes("review.title_max_length") &&
      uniappOrderDetail.includes("review.content_min_length") &&
      uniappOrderDetail.includes("review.content_max_length"),
  },
  {
    name: "backend app review path reads commerce config with defaults",
    pass:
      service.includes("systemConfigClient.commerce()") &&
      service.includes("DEFAULT_REVIEW_MEDIA_MAX_COUNT") &&
      service.includes("DEFAULT_REVIEW_TITLE_MAX_LENGTH") &&
      service.includes("DEFAULT_REVIEW_CONTENT_MIN_LENGTH") &&
      service.includes("DEFAULT_REVIEW_CONTENT_MAX_LENGTH") &&
      service.includes("private ReviewLimits reviewLimits()"),
  },
  {
    name: "backend validates app review title, content, and media count",
    pass:
      service.includes("normalizeMediaObjectKeys(request.getMediaObjectKeys(), limits.mediaMaxCount())") &&
      service.includes("validateAppReviewText(title, content, limits)") &&
      service.includes("Review media limit is") &&
      service.includes("Review title must be at most") &&
      service.includes("Review content must be at least") &&
      service.includes("Review content must be at most"),
  },
  {
    name: "backend preserves MinIO object-key validation",
    pass:
      service.includes('validateUploadedObjectKey(key, "commerce/products/product_review/", "Review media")') &&
      service.includes("must be uploaded through MinIO and saved as an object key"),
  },
  {
    name: "unit test covers public review config limits",
    pass:
      test.includes("submitAppReviewValidatesPublicReviewConfigLimits") &&
      test.includes("review.media_max_count") &&
      test.includes("review.title_max_length") &&
      test.includes("review.content_min_length") &&
      test.includes("review.content_max_length") &&
      test.includes("Media limit is 1"),
  },
];

const failed = checks.filter((check) => !check.pass);
for (const check of checks) {
  console.log(`${check.pass ? "PASS" : "FAIL"} ${check.name}`);
}
if (failed.length > 0) {
  console.error("Commerce review config guard validation failed:");
  for (const check of failed) console.error(`- ${check.name}`);
  process.exit(1);
}
console.log(`Commerce review config guard validation passed (${checks.length} checks).`);
