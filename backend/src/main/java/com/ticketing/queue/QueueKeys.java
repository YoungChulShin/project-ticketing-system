package com.ticketing.queue;

public final class QueueKeys {

    public static final String SEQ = "queue:seq";
    public static final String WAITING = "queue:waiting";
    public static final String ELIGIBLE = "queue:eligible";
    public static final String ACTIVE = "queue:active";
    public static final String DEQUEUED = "queue:dequeued";

    private QueueKeys() {
    }
}
