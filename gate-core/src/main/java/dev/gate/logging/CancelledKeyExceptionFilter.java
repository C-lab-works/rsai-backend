package dev.gate.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

public class CancelledKeyExceptionFilter extends Filter<ILoggingEvent> {

    private static final String CANCELLED_KEY_EXCEPTION = "java.nio.channels.CancelledKeyException";

    @Override
    public FilterReply decide(ILoggingEvent event) {
        IThrowableProxy proxy = event.getThrowableProxy();
        if (proxy != null && CANCELLED_KEY_EXCEPTION.equals(proxy.getClassName())) {
            return FilterReply.DENY;
        }
        String message = event.getFormattedMessage();
        if (message != null && message.contains("CancelledKeyException")) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
