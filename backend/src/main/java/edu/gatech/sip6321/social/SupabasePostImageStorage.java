package edu.gatech.sip6321.social;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

/** Minimal Storage REST adapter. No redirects, response-body buffering, or credential logging. */
public final class SupabasePostImageStorage implements PostImageStorage {
    @Override
    public void close() {
        client.close();
    }

    private final HttpClient client;
    private final URI origin;
    private final String bucket, key;

    SupabasePostImageStorage(HttpClient client, URI origin, String bucket, String key) {
        this.client = client;
        this.origin = origin;
        this.bucket = bucket;
        this.key = key;
    }

    @Override
    public String upload(ValidatedImage image) {
        String reference = bucket + "/posts/" + UUID.randomUUID() + "." + image.extension();
        try {
            exchange(
                    HttpRequest.newBuilder(object(reference))
                            .header("Content-Type", image.mediaType())
                            .header("x-upsert", "false")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(image.bytes())));
            return reference;
        } catch (SocialFailure failure) {
            // A timeout can follow a successful remote upload; best-effort compensation uses
            // only this generated path. A late server completion can still leave an orphan.
            try {
                delete(reference);
            } catch (RuntimeException ignored) {
                LoggerFactory.getLogger(getClass())
                        .warn("Social upload cleanup failed; orphan reconciliation required");
            }
            throw failure;
        }
    }

    @Override
    public void delete(String reference) {
        if (!reference.matches(
                java.util.regex.Pattern.quote(bucket)
                        + "/posts/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(jpg|png)"))
            throw SocialFailure.storage();
        exchange(HttpRequest.newBuilder(object(reference)).DELETE());
    }

    private URI object(String reference) {
        return origin.resolve("/storage/v1/object/" + reference);
    }

    private void exchange(HttpRequest.Builder builder) {
        var request =
                builder.timeout(Duration.ofSeconds(10))
                        .header("apikey", key)
                        .header("Authorization", "Bearer " + key)
                        .build();
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            int status = pending.get(10, TimeUnit.SECONDS).statusCode();
            if (status < 200 || status >= 300) throw SocialFailure.storage();
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw SocialFailure.storage();
        } catch (ExecutionException | TimeoutException e) {
            pending.cancel(true);
            throw SocialFailure.storage();
        }
    }
}
