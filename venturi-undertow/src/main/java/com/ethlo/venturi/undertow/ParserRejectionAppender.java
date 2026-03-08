package com.ethlo.venturi.undertow;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.concurrent.atomic.LongAdder;

public final class ParserRejectionAppender extends AppenderBase<ILoggingEvent>
{
    // Global static counter for instant access
    public static final LongAdder PARSER_REJECTIONS = new LongAdder();

    @Override
    protected void append(final ILoggingEvent event)
    {
        // UT005014 = Failed to parse request
        // UT005006 = Header too large
        //System.out.println(event.getMessage());
        final String msg = event.getMessage();
        if (msg != null && msg.contains("UT0050"))
        {
            PARSER_REJECTIONS.increment();
        }
    }
}