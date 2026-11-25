package com.liveflow.core.room.message;

import java.util.List;
import java.util.Set;

/** 读取影响房间历史的共享处理阻断。 */
public interface RoomHistoryProtection {

    /** @param roomIds 需要检查的规范房间 UUID
     * @return 有房间级或历史分区级阻断的房间
     */
    Set<String> blockedRooms(List<String> roomIds);

}
