package edu.gatech.sip6321.social;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.HashSet;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

public final class ImageMultipart {
    private ImageMultipart() {}

    public static MultipartFile read(MultipartHttpServletRequest request, boolean allowText)
            throws IOException, ServletException {
        // Inspect raw parts too: duplicate files/fields and file-like protected fields all fail.
        var seen = new HashSet<String>();
        for (var part : request.getParts()) {
            String name = part.getName();
            if (!seen.add(name)
                    || !(name.equals("image") || (allowText && name.equals("textContent"))))
                throw SocialFailure.invalid();
            if (name.equals("textContent")) {
                if (part.getSubmittedFileName() != null) throw SocialFailure.invalid();
                if (part.getSize() > SocialText.MAX_BYTES) throw SocialFailure.large();
            }
        }
        // MockMvc file requests may expose files/parameters without servlet Parts.
        for (var entry : request.getMultiFileMap().entrySet())
            if (!entry.getKey().equals("image") || entry.getValue().size() != 1)
                throw SocialFailure.invalid();
        for (var entry : request.getParameterMap().entrySet())
            if (!(allowText && entry.getKey().equals("textContent"))
                    || entry.getValue().length != 1) throw SocialFailure.invalid();
        var file = request.getFile("image");
        if (file == null) throw SocialFailure.invalid();
        return file;
    }
}
