package com.liveflow.infrastructure.room;

import java.util.function.LongSupplier;

/** 同时约束本机回源启动速率和未结束的源查询。 */
final class RoomSourceBudget {
    private final int rate;
    private final int burst;
    private final int concurrency;
    private final LongSupplier clock;
    private double tokens;
    private long previous;
    private int inFlight;

    /** @param rate 每秒补充的令牌数
     * @param burst 桶容量和初始令牌数
     * @param concurrency 最大在途查询数
     * @param clock 提供单调递增的纳秒时间
     */
    RoomSourceBudget(int rate, int burst, int concurrency, LongSupplier clock) {
        this.rate = rate;
        this.burst = burst;
        this.concurrency = concurrency;
        this.clock = clock;
        tokens = burst;
        previous = clock.getAsLong();
    }

    /** @return 接入结果，获准者必须在实际查询结束后释放 */
    synchronized Decision tryStart() {
        if (inFlight == concurrency) return Decision.CONCURRENCY_LIMIT;
        long now = clock.getAsLong();
        tokens = Math.min(burst, tokens + (now - previous) / 1_000_000_000d * rate);
        previous = now;
        if (tokens < 1) return Decision.RATE_LIMIT;
        tokens--;
        inFlight++;
        return Decision.ADMITTED;
    }

    /** 释放在途额度。已用的速率令牌按时间重新补充。 */
    synchronized void finish() {
        inFlight--;
    }

    /** @return 当前尚未结束的源查询数 */
    synchronized int inFlight() {
        return inFlight;
    }

    enum Decision { ADMITTED, RATE_LIMIT, CONCURRENCY_LIMIT }
}
