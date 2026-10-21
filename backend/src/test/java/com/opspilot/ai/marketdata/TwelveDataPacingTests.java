package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class TwelveDataPacingTests {
    @Test
    void concurrentRequestsCannotPassWhileFirstIsSending() throws Exception {
        var properties = new TwelveDataProperties(URI.create("https://example.invalid"),
                "test", Duration.ofSeconds(1), Duration.ofSeconds(1));
        var interceptors = new ArrayList<org.springframework.http.client.ClientHttpRequestInterceptor>();
        new MarketDataConfiguration().twelveDataRestClient(properties).mutate()
                .requestInterceptors(interceptors::addAll);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondReady = new java.util.concurrent.CountDownLatch(1);
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        Thread first = new Thread(() -> {
            try {
                interceptors.getFirst().intercept(null, null, (r, b) -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) {
                        throw new java.io.IOException(e);
                    }
                    return null;
                });
            } catch (Throwable e) { errors.add(e); }
        });
        Thread second = new Thread(() -> {
            secondReady.countDown();
            try { interceptors.getFirst().intercept(null, null, (r, b) -> null); }
            catch (java.io.IOException expected) { /* 被主动中断的等待不能发送请求。 */ }
        });
        first.start();
        try {
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            second.start();
            assertThat(secondReady.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (second.getState() == Thread.State.RUNNABLE && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            // 不是在已释放的时隙之外睡眠：第一个实际发送尚未结束，必须阻塞在同一锁。
            assertThat(second.getState()).isEqualTo(Thread.State.BLOCKED);
        } finally {
            second.interrupt();
            release.countDown();
            first.join(3000);
            second.join(3000);
        }
        assertThat(errors).isEmpty();
        assertThat(first.isAlive() || second.isAlive()).isFalse();
    }

    @Test
    void spacesEveryRequestAndAllowsFirstImmediately() throws Exception {
        var now = new AtomicLong(10);
        var waits = new ArrayList<Long>();
        var gate = new TwelveDataPacer(Duration.ofNanos(9), now::get, n -> {
            waits.add(n);
            now.addAndGet(n);
        });
        gate.acquire();
        assertThat(waits).isEmpty();
        now.addAndGet(3);
        gate.acquire();
        gate.acquire();
        assertThat(waits).containsExactly(6L, 9L);
        now.addAndGet(20);
        gate.acquire();
        assertThat(waits).hasSize(2);
    }

    @Test
    void interruptionStopsRequestAndPreservesFlag() throws Exception {
        var gate = new TwelveDataPacer(Duration.ofSeconds(9), () -> 0L,
                n -> { throw new InterruptedException(); });
        gate.acquire();
        try {
            assertThatThrownBy(gate::acquire).isInstanceOf(java.io.IOException.class)
                    .hasMessage("行情请求等待已中断").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // 共享客户端必须在每个真实请求前节流，不能只在五日批次之间等待。
    @Test
    void sharedClientHasRequestGate() {
        var properties = new TwelveDataProperties(URI.create("https://example.invalid"),
                "test", Duration.ofSeconds(1), Duration.ofSeconds(1));
        var client = new MarketDataConfiguration().twelveDataRestClient(properties);
        client.mutate().requestInterceptors(list -> assertThat(list).isNotEmpty());
    }
}
