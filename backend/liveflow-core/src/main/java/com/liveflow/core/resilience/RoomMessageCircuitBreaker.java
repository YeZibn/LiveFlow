package com.liveflow.core.resilience;

import java.util.Optional;

/** 保护一个 LiveFlow 进程内的弹幕发送链路。 */
public interface RoomMessageCircuitBreaker {
    /** 发生实际故障的发送依赖，用于区分业务拒绝及统计阶段。 */
    enum Dependency { MYSQL, REDIS, KAFKA }

    /**
     * 在访问发送链路的依赖前领取许可。
     * @return 可结算的许可，断路器拒绝时为空
     */
    Optional<Permit> tryAcquire();

    /** 在原发送轮次内仅结算一次的许可。 */
    interface Permit {
        /** 记录新提交收到 Broker 确认。 */
        void succeeded();

        /**
         * 记录一次发送依赖故障。
         * @param dependency 实际故障所在的依赖
         * @param failure 内部依赖异常或带故障来源的公开业务异常
         */
        void failed(Dependency dependency, Throwable failure);

        /** 归还许可，当前结果不会成为成功、失败或慢调用样本。 */
        void release();
    }
}
