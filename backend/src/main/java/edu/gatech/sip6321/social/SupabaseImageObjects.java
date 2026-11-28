package edu.gatech.sip6321.social;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/** Bounded Storage requests for server-generated image references. */
public final class SupabaseImageObjects implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient client;
    private final URI origin;
    private final String key;

    SupabaseImageObjects(HttpClient client, URI origin, String key) {
        this.client = client;
        this.origin = origin;
        this.key = key;
    }

    public static SupabaseImageObjects configured(Environment env) {
        URI origin;
        String key = env.getProperty("SUPABASE_STORAGE_SERVICE_ROLE_KEY", "");
        try {
            origin = URI.create(env.getProperty("SUPABASE_URL", ""));
            if (!"https".equals(origin.getScheme())
                    || origin.getHost() == null
                    || origin.getUserInfo() != null
                    || origin.getQuery() != null
                    || origin.getFragment() != null
                    || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                    || !key.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Storage requires HTTPS SUPABASE_URL and a server-only service-role JWT.");
        }
        return new SupabaseImageObjects(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                origin,
                key);
    }

    public void upload(String reference, ValidatedImage image) {
        try {
            exchange(
                    HttpRequest.newBuilder(object("", reference))
                            .header("Content-Type", image.mediaType())
                            .header("x-upsert", "false")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(image.bytes())),
                    HttpResponse.BodyHandlers.discarding());
        } catch (SocialFailure failure) {
            // The request may have timed out after creating this new, uniquely named object.
            try {
                delete(reference);
            } catch (RuntimeException ignored) {
                LoggerFactory.getLogger(getClass())
                        .warn("Image upload cleanup failed; orphan reconciliation required");
            }
            throw failure;
        }
    }

    public void delete(String reference) {
        exchange(
                HttpRequest.newBuilder(object("", reference)).DELETE(),
                HttpResponse.BodyHandlers.discarding());
    }

    public byte[] download(String reference, String mediaType) {
        var response =
                exchange(
                        HttpRequest.newBuilder(object("authenticated/", reference))
                                .header("Accept", mediaType)
                                .GET(),
                        info ->
                                info.statusCode() == 200
                                        ? new BoundedBody()
                                        : HttpResponse.BodySubscribers.replacing(new byte[0]));
        if (response.statusCode() != 200
                || response.body().length == 0
                || !response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .equalsIgnoreCase(mediaType)) throw SocialFailure.storage();
        return response.body();
    }

    private URI object(String prefix, String reference) {
        if (!reference.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)+\\.(png|jpg)"))
            throw SocialFailure.storage();
        return origin.resolve("/storage/v1/object/" + prefix + reference);
    }

    private <T> HttpResponse<T> exchange(
            HttpRequest.Builder builder, HttpResponse.BodyHandler<T> handler) {
        var request =
                builder.timeout(TIMEOUT)
                        .header("apikey", key)
                        .header("Authorization", "Bearer " + key)
                        .build();
        var pending = client.sendAsync(request, handler);
        try {
            var response = pending.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw SocialFailure.storage();
            return response;
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw SocialFailure.storage();
        } catch (ExecutionException | TimeoutException e) {
            pending.cancel(true);
            throw SocialFailure.storage();
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /** Enforce the download limit even when the upstream omits Content-Length. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) size += item.remaining();
            if (size > ValidatedImage.MAX_BYTES) {
                subscription.cancel();
                delegate.onError(new IOException("Image exceeds size limit."));
            } else {
                delegate.onNext(items);
            }
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
