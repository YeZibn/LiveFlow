package com.liveflow.core.room.message;

/** 弹幕模块的公开业务错误，内部依赖异常由接口层映射。 */
public final class RoomMessageFailure extends RuntimeException {
    public enum Code {
        INVALID_REQUEST("请检查请求字段"),
        ROOM_NOT_FOUND("房间不存在"),
        TICKET_INVALID("连接票据无效或已过期"),
        MESSAGE_ID_CONFLICT("该提交标识已用于另一条消息"),
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

    /** @param code 接口使用的公开错误码 */
    public RoomMessageFailure(Code code) {
        this(code, 0);
    }

    /**
     * @param code 接口使用的公开错误码
     * @param retryAfterSeconds 距离下一次可提交的整数秒，零表示没有等待提示
     */
    public RoomMessageFailure(Code code, long retryAfterSeconds) {
        super(code.message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** @return 公开错误码 */
    public Code code() {
        return code;
    }

    /** @return 允许重试前的整数秒数 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
