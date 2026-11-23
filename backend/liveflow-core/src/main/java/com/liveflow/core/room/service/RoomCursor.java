package com.liveflow.core.room.service;

import com.liveflow.core.room.RoomUuid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 房间目录的稳定分页边界，仅包含排序键和搜索条件摘要。 */
record RoomCursor(LocalDateTime createdAt, String roomId) {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,256}");
    private static final String VERSION = "1";
    private static final long MIN_MILLIS = Instant.parse("1000-01-01T00:00:00Z").toEpochMilli();
    private static final long MAX_MILLIS = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

    /**
     * 验证并解码同一搜索条件的游标。
     * @param token API 返回的游标，缺省时为 null
     * @param keyword 规范化后的查询词
     * @return 排序边界，首页返回 null
     * @throws RoomService.InvalidDirectoryQueryException 游标格式、版本或查询条件无效
     */
    static RoomCursor decode(String token, String keyword) {
        if (token == null) {
            return null;
        }
        if (!TOKEN.matcher(token).matches()) {
            throw new RoomService.InvalidDirectoryQueryException();
        }
        try {
            String data = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
            String[] fields = data.split(":", -1);
            if (fields.length == 4 && VERSION.equals(fields[0]) && RoomUuid.isCanonical(fields[2])
                    && fingerprint(keyword).equals(fields[3])) {
                long millis = Long.parseLong(fields[1]);
                if (millis >= MIN_MILLIS && millis <= MAX_MILLIS) {
                    var cursor = new RoomCursor(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC),
                            fields[2]);
                    // 只接受服务端生成的规范编码形式，包括毫秒字段和 Base64URL 尾部。
                    if (cursor.encode(keyword).equals(token)) {
                        return cursor;
                    }
                }
            }
        } catch (IllegalArgumentException failure) {
            throw new RoomService.InvalidDirectoryQueryException();
        }
        throw new RoomService.InvalidDirectoryQueryException();
    }

    /**
     * 编码当前页最后一条记录的排序边界。
     * @param keyword 规范化后的查询词
     * @return 不带填充的 Base64URL 游标
     */
    String encode(String keyword) {
        long millis = createdAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        String data = VERSION + ":" + millis + ":" + roomId + ":" + fingerprint(keyword);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 按 UTF-8 编码计算规范化关键词的摘要，用于检查游标是否属于同一搜索。
     * @param keyword 规范化关键词，浏览全部时为空字符串
     * @return 小写十六进制 SHA-256 摘要
     * @throws IllegalStateException 当前运行时无法提供 SHA-256
     */
    private static String fingerprint(String keyword) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(keyword.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 Java 运行时无法计算 SHA-256", failure);
        }
    }
}
