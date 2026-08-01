package com.shvoy;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Captures log output for a given logger for the duration of a test. Needed
 * wherever a value is deliberately never returned via the API and only ever
 * reaches the console log — the raw registration/invite tokens in
 * RegistrationService and InvitationService — so tests can still assert on
 * it without weakening that guarantee.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logbackLogger;
    private final ListAppender<ILoggingEvent> appender;

    public LogCapture(Class<?> loggerClass) {
        this.logbackLogger = (Logger) LoggerFactory.getLogger(loggerClass);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logbackLogger.addAppender(appender);
    }

    public String firstMessageContaining(String substring) {
        return appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(msg -> msg.contains(substring))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No log message containing \"" + substring + "\" was captured"));
    }

    public static String valueAfter(String message, String key) {
        return message.substring(message.indexOf(key) + key.length());
    }

    @Override
    public void close() {
        logbackLogger.detachAppender(appender);
    }
}
