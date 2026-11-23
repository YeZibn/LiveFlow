package com.liveflow.core.room;

import java.util.regex.Pattern;

/** 房间接口使用的标准小写 UUID v4 格式。 */
public final class RoomUuid {

    private static final Pattern FORMAT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private RoomUuid() {}

    /**
     * 校验对外房间 UUID 或采用相同格式的客户端提交标识。
     * @param value 待校验字符串
     * @return 是否为标准小写 UUID v4
     */
    public static boolean isCanonical(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }
}
