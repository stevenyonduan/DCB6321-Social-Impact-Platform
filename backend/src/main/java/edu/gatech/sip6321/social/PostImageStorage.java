package edu.gatech.sip6321.social;

/** Backend-generated durable object reference, never a client URL or expiring signed URL. */
public interface PostImageStorage extends AutoCloseable {
    String upload(ValidatedImage image);

    void delete(String reference);

    @Override
    default void close() {}
}
