package edu.gatech.sip6321.social;

final class SocialText {
    static final int MAX_BYTES = 16384;

    private SocialText() {}

    static boolean meaningful(String text) {
        return text != null
                && !text.codePoints()
                        .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }

    static String validate(String text) {
        if (text == null) return null;
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
            throw SocialFailure.large();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) throw SocialFailure.invalid();
            if (Character.isHighSurrogate(c)
                    && (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))))
                throw SocialFailure.invalid();
        }
        return text;
    }

    static void content(String text, String image) {
        validate(text);
        if (!meaningful(text) && image == null) throw SocialFailure.invalid();
    }
}
