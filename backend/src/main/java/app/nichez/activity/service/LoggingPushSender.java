package app.nichez.activity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in until a push vendor is wired: records that a push would have gone out, without its token
 * or text. A real {@link PushSender} is added as a {@code @Primary} bean and replaces this one.
 */
@Component
class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void send(String deviceToken, String title, String body) {
        log.debug("Push not sent: no push vendor is configured");
    }
}
