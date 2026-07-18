package com.ticketing.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트에서 시간을 조작할 수 있는 Clock.
 * 프로덕션 코드가 Clock을 주입받는 설계 덕에, 이 구현체로 갈아끼우면
 * sleep 없이 "61초 뒤" 같은 상황을 즉시 만들 수 있다.
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant initial, ZoneId zone) {
        this.instant = initial;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    public void set(Instant newInstant) {
        instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
