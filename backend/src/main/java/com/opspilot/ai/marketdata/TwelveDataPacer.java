package com.opspilot.ai.marketdata;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

/** 在本进程内串行分配 Twelve Data 请求时隙，避免补缺请求瞬间突发。 */
final class TwelveDataPacer {
    @FunctionalInterface
    interface Sleeper { void sleep(long nanos) throws InterruptedException; }

    private final long interval;
    private final LongSupplier time;
    private final Sleeper sleeper;
    private boolean started;
    private long last;

    TwelveDataPacer(Duration interval) {
        this(interval, System::nanoTime, nanos ->
                Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)));
    }

    TwelveDataPacer(Duration interval, LongSupplier time, Sleeper sleeper) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("请求间隔必须大于零");
        }
        this.interval = interval.toNanos();
        this.time = time;
        this.sleeper = sleeper;
    }

    // 等待也持有锁：并发调用不能预占同一时隙；单调时钟不受系统校时影响。
    synchronized void acquire() throws IOException {
        try {
            if (started) {
                long remaining;
                while ((remaining = interval - (time.getAsLong() - last)) > 0) {
                    sleeper.sleep(remaining);
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            last = time.getAsLong();
            started = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("行情请求等待已中断");
        }
    }
}
