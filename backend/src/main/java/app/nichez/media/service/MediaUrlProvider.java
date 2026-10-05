package app.nichez.media.service;

/**
 * Turns an object key into a URL a client can fetch for a limited time ({@code media.ttl}).
 * Which implementation runs is {@code media.url-provider}: presigned bucket URLs locally, playback
 * Worker tokens in production. Keys are never sent to clients any other way.
 */
public interface MediaUrlProvider {

    /** A short-lived URL for the object, or null when {@code key} is null. */
    String urlFor(String key);
}
