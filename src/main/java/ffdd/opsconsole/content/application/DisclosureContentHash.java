package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.dto.DisclosureChapterInput;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class DisclosureContentHash {
    private DisclosureContentHash() {}

    public static String from(DisclosureDraftRequest request) {
        MessageDigest digest = sha256();
        add(digest, request.version());
        add(digest, request.jurisdiction());
        add(digest, request.languageScope());
        add(digest, request.effectiveDate());
        add(digest, String.valueOf(Boolean.TRUE.equals(request.requiresReack())));
        add(digest, request.zh());
        add(digest, request.vi());
        add(digest, request.en());
        List<DisclosureChapterInput> chapters = request.chapters() == null ? List.of() : request.chapters();
        for (DisclosureChapterInput chapter : chapters) {
            add(digest, chapter.no());
            add(digest, chapter.zhTitle());
            add(digest, chapter.viTitle());
            add(digest, chapter.enTitle());
            add(digest, chapter.zhBody());
            add(digest, chapter.viBody());
            add(digest, chapter.enBody());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String from(DisclosureDraftView draft, List<DisclosureChapterView> chapters) {
        return from(new DisclosureDraftRequest(
                draft.version(), draft.jurisdiction(), draft.languageScope(), draft.effectiveDate(),
                draft.requiresReack(), draft.zh(), draft.vi(), draft.en(),
                chapters.stream().map(chapter -> new DisclosureChapterInput(
                        chapter.no(), chapter.zh(), chapter.vi(), chapter.en(),
                        chapter.zhBody(), chapter.viBody(), chapter.enBody())).toList(),
                null, null, "system", "snapshot"));
    }

    public static String ofParts(String... parts) {
        MessageDigest digest = sha256();
        if (parts != null) {
            for (String part : parts) add(digest, part);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
