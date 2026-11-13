package com.ailabs.canary;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One INFO line every heartbeat.interval (default 30s), so an idle pod still shows it is logging. */
@Component
public class Heartbeat {
    private static final Logger log = LoggerFactory.getLogger(Heartbeat.class);
    private final AtomicLong beats = new AtomicLong();

    @Scheduled(fixedDelayString = "${heartbeat.interval:30s}")
    public void beat() {
        log.info("heartbeat #{}", beats.incrementAndGet());
    }
}
