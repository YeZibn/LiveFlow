package com.liveflow.core.room.service;

import org.springframework.dao.DataAccessException;

/** 提供房间存在性读取及已提交房间的缓存更新。 */
public interface RoomExistenceStore {

    /**
     * 取得已确认的存在性结果。源数据来自 MySQL。
     * @param roomId 已通过格式校验的标准小写房间 UUID
     * @return 已确认存在为 true，已确认不存在为 false
     * @throws Unavailable 等待超时或回源额度不足，当前无法确认
     * @throws DataAccessException MySQL 查询失败
     */
    boolean exists(String roomId);

    /**
     * 尝试预热已提交的房间。缓存故障由适配器记录，调用方仍使用事务结果。
     * @param roomId 创建事务已确认提交的房间 UUID
     */
    void warmCreated(String roomId);

    /** 本机保护或等待期限导致的查房拒绝。 */
    final class Unavailable extends RuntimeException {
        public Unavailable() {
            super("房间存在性暂时无法确认");
        }
    }
}
