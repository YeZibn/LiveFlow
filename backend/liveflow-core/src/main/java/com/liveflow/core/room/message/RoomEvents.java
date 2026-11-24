package com.liveflow.core.room.message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.UUID;

/** 弹幕提交、正式事件和终态回执共用的领域契约。 */
public final class RoomEvents {
    public static final long MAX_SEQUENCE = Long.MAX_VALUE - 1;
    public static final int RETRY_SECONDS = 600;
    public static final int ACCEPT_SECONDS = 720;
    public static final int RECEIPT_SECONDS = 1800;
    public static final DateTimeFormatter UTC_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private RoomEvents() {}

    /**
     * @param userId 当前认证账号 ID
     * @param sessionId 已验证的会话 UUID
     * @param roomId 已校验的房间 UUID
     */
    public record Scope(long userId, UUID sessionId, String roomId) {}

    /**
     * @param submissionId 当前预约的公开查询 UUID
     * @param admittedAt 从 Redis 读取的首次许可时间
     */
    public record Queued(UUID submissionId, Instant admittedAt) {
        /** @return 同标识可重试到的绝对时间 */
        public Instant retryUntil() {
            return admittedAt.plusSeconds(RETRY_SECONDS);
        }

        /** @return 预约回执的绝对查询截止 */
        public Instant receiptExpiresAt() {
            return admittedAt.plusSeconds(RECEIPT_SECONDS);
        }
    }

    /**
     * @param schemaVersion Kafka 提交契约版本
     * @param submissionId Redis 预约 UUID
     * @param roomId 也是 Kafka 提交分区键的房间 UUID
     * @param userId 来自当前认证账号的十进制标识
     * @param sessionId 当前会话 UUID
     * @param clientMessageId 重试中保持不变的客户端 UUID
     * @param content 规范化的单行正文
     * @param contentHash 正文的 SHA-256 摘要
     * @param senderName 首次许可时固定的显示名
     * @param admittedAt 首次许可时的 UTC 毫秒字符串
     */
    public record Submission(int schemaVersion, UUID submissionId, String roomId, String userId, UUID sessionId,
            String clientMessageId, String content, String contentHash, String senderName, String admittedAt) {}

    /**
     * @param schemaVersion Kafka 正式事件契约版本
     * @param roomId 房间 UUID，与记录键相同
     * @param roomSeq 同房间严格递增的十进制序号
     * @param messageId 首次正式接收时生成的 UUID
     * @param userId 内部持久化的十进制账号标识
     * @param senderName 首次许可时的显示名快照
     * @param content 正式接收的单行正文
     * @param sentAt 正式接收的 UTC 毫秒字符串
     */
    public record Accepted(int schemaVersion, String roomId, String roomSeq, UUID messageId,
            String userId, String senderName, String content, String sentAt) {}

    /**
     * @param schemaVersion Kafka 回执契约版本
     * @param submissionId 被正式裁决的预约 UUID
     * @param roomId 回执作用域中的房间 UUID
     * @param userId 回执作用域中的十进制账号标识
     * @param sessionId 回执作用域中的会话 UUID
     * @param status accepted 或 rejected
     * @param messageId 接收时的正式 UUID，拒绝时为空
     * @param roomSeq 接收时的正式序号，拒绝时为空
     * @param sentAt 接收时的 UTC 毫秒时间，拒绝时为空
     * @param code 拒绝原因，接收时为空
     * @param expiresAt 自本次预约首次许可起计算的绝对截止
     */
    public record Receipt(int schemaVersion, UUID submissionId, String roomId, String userId, UUID sessionId,
            String status, UUID messageId, String roomSeq, String sentAt, String code, String expiresAt) {}

    /**
     * @param roomSeq 房间内的十进制序号
     * @param messageId 用于复用或去重的正式消息 UUID
     * @param senderName 接收方可见的显示名快照
     * @param content 公开的纯文本正文
     * @param sentAt 供浏览器转换当地时间的 UTC 毫秒字符串
     */
    public record Message(String roomSeq, UUID messageId, String senderName, String content, String sentAt) {}

    /**
     * @param content 已规范化的正文
     * @return UTF-8 正文的 SHA-256 小写十六进制摘要
     */
    public static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", impossible);
        }
    }
}
