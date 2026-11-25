package com.liveflow.core.room.message;

import java.util.List;
import java.util.Set;

/** 读取和记录影响房间历史的共享处理阻断。 */
public interface RoomHistoryProtection {

    /** @param roomIds 需要检查的规范房间 UUID
     * @return 有房间级或历史分区级阻断的房间
     */
    Set<String> blockedRooms(List<String> roomIds);

    /** @param roomId 存在不可变历史冲突的房间
     * @param offset 无法提交的 Kafka 批次起点，诊断直接写入时可空
     */
    void recordConflict(String roomId, Long offset);
}
