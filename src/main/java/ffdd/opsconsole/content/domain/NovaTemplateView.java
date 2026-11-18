package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.canonical.RetiredBrandGate;

public record NovaTemplateView(
        String channel,
        String name,
        String cta,
        String version,
        String titleZh,
        String bodyZh,
        String titleVi,
        String bodyVi,
        String titleEn,
        String bodyEn,
        String status,
        @com.fasterxml.jackson.annotation.JsonIgnore String translationsJson) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public NovaTemplateView {}

    public NovaTemplateView(String channel, String name, String cta, String version, String titleZh,
            String bodyZh, String titleVi, String bodyVi, String titleEn, String bodyEn, String status) {
        this(channel, name, cta, version, titleZh, bodyZh, titleVi, bodyVi, titleEn, bodyEn, status, null);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("translations")
    public java.util.Map<String, NotificationTranslation> translations() {
        return NotificationTranslation.read(translationsJson);
    }

    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String revision() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String field : new String[] {channel, name, cta, version, titleZh, bodyZh, titleVi, bodyVi,
                    titleEn, bodyEn, status, translationsJson}) {
                if (field == null) {
                    digest.update((byte) 0);
                } else {
                    byte[] bytes = field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    digest.update((byte) 1);
                    digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public boolean carriesRetiredBrand() {
        return RetiredBrandGate.anyCarriesRetiredBrand(
                titleZh, bodyZh, titleVi, bodyVi, titleEn, bodyEn) || translations().values().stream()
                .anyMatch(value -> RetiredBrandGate.anyCarriesRetiredBrand(value.title(), value.body()));
    }
}
