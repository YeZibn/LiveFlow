package com.liveflow.infrastructure.message;

import com.liveflow.core.room.RoomUuid;
import com.liveflow.core.room.message.RoomEvents;
import com.liveflow.core.room.message.RoomEvents.Accepted;
import com.liveflow.core.room.message.RoomEvents.Message;
import com.liveflow.core.room.message.RoomEvents.Receipt;
import com.liveflow.core.room.message.RoomEvents.Submission;
import com.liveflow.core.room.message.RoomMessageService;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** 在各消费边界验证 Kafka 键和 V1 JSON 契约。 */
record RoomEventCodec(JsonMapper json, ObjectReader reader) {
    RoomEventCodec(JsonMapper json) {
        this(json, json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    }

    /**
     * @param key 房间分区键
     * @param value UTF-8 JSON
     * @return 可进行正式裁决的提交
     * @throws IllegalArgumentException 不支持的版本或字段
     */
    Submission submission(String key, String value) {
        var input = fields(value, "submissionId", "roomId", "userId", "sessionId", "clientMessageId",
                "content", "contentHash", "senderName", "admittedAt");
        require(RoomUuid.isCanonical(input.get("submissionId").asString())
                && RoomUuid.isCanonical(input.get("sessionId").asString()));
        Submission event = json.treeToValue(input, Submission.class);
        common(event.schemaVersion(), event.roomId(), event.userId());
        time(event.admittedAt());
        require(event.roomId().equals(key) && id(event.submissionId()) && id(event.sessionId())
                && RoomUuid.isCanonical(event.clientMessageId()) && name(event.senderName())
                && validContent(event.content()) && RoomEvents.hash(event.content()).equals(event.contentHash()));
        return event;
    }

    /**
     * @param key 房间分区键
     * @param value UTF-8 JSON
     * @return 可持久化和广播的正式事件
     */
    Accepted accepted(String key, String value) {
        var input = fields(value, "roomId", "roomSeq", "messageId", "userId", "senderName", "content", "sentAt");
        require(RoomUuid.isCanonical(input.get("messageId").asString()));
        Accepted event = json.treeToValue(input, Accepted.class);
        common(event.schemaVersion(), event.roomId(), event.userId());
        time(event.sentAt());
        number(event.roomSeq(), RoomEvents.MAX_SEQUENCE);
        require(event.roomId().equals(key) && id(event.messageId()) && name(event.senderName())
                && validContent(event.content()));
        return event;
    }

    /**
     * @param key 回执分区键
     * @param value UTF-8 JSON
     * @return 已校验的终态投影数据
     */
    Receipt receipt(String key, String value) {
        var input = fields(value, "submissionId", "roomId", "userId", "sessionId", "status", "expiresAt");
        require(RoomUuid.isCanonical(input.get("submissionId").asString())
                && RoomUuid.isCanonical(input.get("sessionId").asString()));
        if ("accepted".equals(input.get("status").asString())) {
            require(input.path("messageId").isString() && RoomUuid.isCanonical(input.get("messageId").asString())
                    && input.path("roomSeq").isString() && input.path("sentAt").isString());
        } else {
            require(input.path("code").isString());
        }
        Receipt event = json.treeToValue(input, Receipt.class);
        common(event.schemaVersion(), event.roomId(), event.userId());
        Instant expires = time(event.expiresAt());
        require(id(event.submissionId()) && event.submissionId().toString().equals(key) && id(event.sessionId()));
        if ("accepted".equals(event.status())) {
            require(id(event.messageId()) && event.code() == null && time(event.sentAt()).isBefore(expires));
            number(event.roomSeq(), RoomEvents.MAX_SEQUENCE);
        } else {
            require("rejected".equals(event.status()) && event.messageId() == null
                    && event.roomSeq() == null && event.sentAt() == null
                    && ("MESSAGE_ID_CONFLICT".equals(event.code()) || "MESSAGE_SUBMISSION_EXPIRED".equals(event.code())));
        }
        return event;
    }

    /**
     * @param event 读自 Kafka 的正式事件
     * @return Redis 房间频道使用的公开 JSON
     */
    String encodeLive(Accepted event) {
        return json.writeValueAsString(new LiveMessage("message", event.roomSeq(), event.messageId(),
                event.senderName(), event.content(), event.sentAt()));
    }

    /**
     * @param value Redis 频道的 UTF-8 JSON
     * @return 可向浏览器广播的消息
     */
    Message decodeLive(String value) {
        var event = json.readValue(value, LiveMessage.class);
        require("message".equals(event.type()) && id(event.messageId()) && name(event.senderName())
                && validContent(event.content()));
        number(event.roomSeq(), RoomEvents.MAX_SEQUENCE);
        time(event.sentAt());
        return new Message(event.roomSeq(), event.messageId(), event.senderName(), event.content(), event.sentAt());
    }

    record LiveMessage(String type, String roomSeq, UUID messageId, String senderName,
            String content, String sentAt) {}

    private JsonNode fields(String value, String... textFields) {
        JsonNode input = reader.readValue(value);
        require(input != null && input.isObject() && input.path("schemaVersion").isIntegralNumber()
                && input.get("schemaVersion").asInt() == 1);
        for (String field : textFields) require(input.path(field).isString());
        return input;
    }

    private static void common(int version, String room, String user) {
        require(version == 1 && RoomUuid.isCanonical(room));
        number(user, Long.MAX_VALUE);
    }

    private static Instant time(String value) {
        Instant result = Instant.parse(value);
        require(RoomEvents.UTC_MILLIS.format(result).equals(value));
        return result;
    }

    private static void number(String value, long max) {
        long parsed = Long.parseLong(value);
        require(parsed > 0 && parsed <= max && Long.toString(parsed).equals(value));
    }

    private static boolean id(UUID value) {
        return value != null && RoomUuid.isCanonical(value.toString());
    }

    private static boolean name(String value) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= 32;
    }

    private static boolean validContent(String value) {
        return RoomMessageService.normalize(value).equals(value);
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("不受支持的弹幕事件字段");
    }
}
