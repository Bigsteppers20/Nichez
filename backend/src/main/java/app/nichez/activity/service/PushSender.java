package app.nichez.activity.service;

/**
 * Hands one push message to the phone vendor (FCM for Android and web, APNs for iOS). The vendor is
 * not chosen yet, so the only implementation today is {@link LoggingPushSender}, which sends nothing.
 * A real one is a bean of this type; the default backs off when it exists.
 */
public interface PushSender {

    /** The title is the niche name, not "Nichez" (FR-10). Tokens and bodies must never be logged. */
    void send(String deviceToken, String title, String body);
}
