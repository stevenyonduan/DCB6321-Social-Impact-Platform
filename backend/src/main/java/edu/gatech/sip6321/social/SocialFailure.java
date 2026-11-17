package edu.gatech.sip6321.social;

public class SocialFailure extends RuntimeException {
    private final int status;
    private final String code;

    public SocialFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    static SocialFailure invalid() {
        return new SocialFailure(400, "INVALID_REQUEST", "Invalid social request.");
    }

    static SocialFailure missing() {
        return new SocialFailure(404, "POST_NOT_FOUND", "Post not found.");
    }

    static SocialFailure commentMissing() {
        return new SocialFailure(404, "COMMENT_NOT_FOUND", "Comment not found.");
    }

    static SocialFailure forbidden() {
        return new SocialFailure(403, "FORBIDDEN", "Only the author may change this resource.");
    }

    static SocialFailure large() {
        return new SocialFailure(
                413, "REQUEST_TOO_LARGE", "Social request exceeds the size limit.");
    }

    static SocialFailure image() {
        return new SocialFailure(415, "INVALID_IMAGE", "Use a valid JPEG or PNG image.");
    }

    static SocialFailure storage() {
        return new SocialFailure(
                503, "STORAGE_UNAVAILABLE", "Image storage is temporarily unavailable.");
    }
}
