package dev.gate.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

import java.nio.channels.CancelledKeyException;

public class CancelledKeyExceptionFilter extends TurboFilter {

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (t instanceof CancelledKeyException) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
