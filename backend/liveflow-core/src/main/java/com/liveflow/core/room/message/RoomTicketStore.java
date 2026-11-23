package com.liveflow.core.room.message;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;

/** 连接票据的短期存储契约。 */
public interface RoomTicketStore {

    /**
     * 写入尚未存在的票据摘要，并为新记录设置到期时间。
     * @param digest 原始票据的摘要
     * @param binding 可信账号、登录会话及房间
     * @param lifetime 票据有效期
     * @return 是否成功签发，摘要已存在时返回 false
     * @throws DataAccessException 无法确认写入
     */
    boolean put(String digest, Binding binding, Duration lifetime);

    /**
     * 原子读取并删除票据绑定。
     * @param digest 客户端提交票据的摘要
     * @return 未过期且未消费的绑定
     * @throws DataAccessException 无法确认消费
     */
    Optional<Binding> consume(String digest);

    /** 由服务端从已认证请求生成的房间连接身份。 */
    record Binding(long userId, UUID sessionId, String roomId) {}
}
