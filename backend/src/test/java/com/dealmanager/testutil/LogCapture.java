package com.dealmanager.testutil;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    public LogCapture(Class<?> type) {
        logger = (Logger) LoggerFactory.getLogger(type);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    public List<ILoggingEvent> events() { return appender.list; }

    public String output() {
        return events().stream().map(e -> e.getFormattedMessage() +
                (e.getThrowableProxy() == null ? "" : "\n" + ThrowableProxyUtil.asString(e.getThrowableProxy())))
                .collect(Collectors.joining("\n"));
    }

    @Override public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }
}
