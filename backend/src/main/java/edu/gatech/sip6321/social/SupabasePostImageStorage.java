package edu.gatech.sip6321.social;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.UUID;

/** Minimal Storage REST adapter. No redirects, response-body buffering, or credential logging. */
public final class SupabasePostImageStorage implements PostImageStorage {
    @Override
    public void close() {
        objects.close();
    }

    private final SupabaseImageObjects objects;
    private final String bucket;

    SupabasePostImageStorage(HttpClient client, URI origin, String bucket, String key) {
        this(new SupabaseImageObjects(client, origin, key), bucket);
    }

    SupabasePostImageStorage(SupabaseImageObjects objects, String bucket) {
        this.objects = objects;
        this.bucket = bucket;
    }

    @Override
    public String upload(ValidatedImage image) {
        String reference = bucket + "/posts/" + UUID.randomUUID() + "." + image.extension();
        objects.upload(reference, image);
        return reference;
    }

    @Override
    public void delete(String reference) {
        if (!reference.matches(
                java.util.regex.Pattern.quote(bucket)
                        + "/posts/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(jpg|png)"))
            throw SocialFailure.storage();
        objects.delete(reference);
    }
}
