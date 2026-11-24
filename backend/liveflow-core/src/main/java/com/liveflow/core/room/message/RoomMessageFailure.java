package com.liveflow.core.room.message;

import com.liveflow.core.resilience.RoomMessageCircuitBreaker.Dependency;
import com.liveflow.core.room.message.RoomEvents.Queued;

/** 弹幕模块的公开业务错误，内部依赖异常由接口层映射。 */
public final class RoomMessageFailure extends RuntimeException {
    public enum Code {
        INVALID_REQUEST("请检查请求字段"),
        ROOM_NOT_FOUND("房间不存在"),
        TICKET_INVALID("连接票据无效或已过期"),
        MESSAGE_ID_CONFLICT("该提交标识已用于另一条消息"),
        MESSAGE_SUBMISSION_UNKNOWN("发送结果未确认，消息可能已送达"),
        MESSAGE_RATE_LIMITED("发送过快，请稍后再试"),
        ROOM_MESSAGES_UNAVAILABLE("弹幕服务暂不可用")
        ;

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }

    private final Code code;
    private final long retryAfterSeconds;
    private final Dependency dependency;
    private final Queued receipt;

    /** @param code 接口使用的公开错误码 */
    public RoomMessageFailure(Code code) {
        this(code, 0);
    }

    /**
     * @param code 接口使用的公开错误码
     * @param retryAfterSeconds 距离下一次可提交的整数秒，零表示没有等待提示
     */
    public RoomMessageFailure(Code code, long retryAfterSeconds) {
        this(code, retryAfterSeconds, null, null, null);
    }

    private RoomMessageFailure(Code code, long retryAfterSeconds, Dependency dependency, Queued receipt,
            Throwable cause) {
        super(code.message, cause);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        this.dependency = dependency;
        this.receipt = receipt;
    }

    /**
     * 保留故障来源，供发送熔断统计使用。
     * @param code HTTP 边界使用的公开错误码
     * @param dependency 实际出现故障的依赖
     * @param cause 原始依赖异常，无程序异常时可为空
     * @return 带故障来源的业务异常
     */
    public static RoomMessageFailure dependency(Code code, Dependency dependency, Throwable cause) {
        return new RoomMessageFailure(code, 0, dependency, null, cause);
    }

    /**
     * 为结果未确认的公开响应附上原预约。
     * @param reservation 同一作用域内已取得的预约信息
     * @return 保留原故障来源、可提示读回执的异常
     */
    public RoomMessageFailure withReceipt(Queued reservation) {
        return new RoomMessageFailure(code, retryAfterSeconds, dependency, reservation, getCause());
    }

    /** @return 公开错误码 */
    public Code code() {
        return code;
    }

    /** @return 账号冷却剩余整数秒数，零表示无等待提示 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** @return 实际故障依赖，纯业务拒绝等非样本结果为空 */
    public Dependency dependency() {
        return dependency;
    }

    /** @return 可公开给当前作用域的预约信息，未知时为空 */
    public Queued receipt() {
        return receipt;
    }
}
