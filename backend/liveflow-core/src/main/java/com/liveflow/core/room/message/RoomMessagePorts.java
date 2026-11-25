package com.liveflow.core.room.message;

import com.liveflow.core.room.message.RoomEvents.Queued;
import com.liveflow.core.room.message.RoomEvents.Receipt;
import com.liveflow.core.room.message.RoomEvents.Scope;
import com.liveflow.core.room.message.RoomEvents.Submission;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 弹幕接入依赖的基础设施边界。 */
public interface RoomMessagePorts {

    /** 管理账号冷却、预约与单次发布许可。 */
    interface Admission {
        /**
         * @param scope 已认证作用域
         * @param clientMessageId 客户端提交标识
         * @param contentHash 已规范化正文的摘要
         * @return 已确认入队的原预约，无绑定时为空
         * @throws RoomMessageFailure 已有预约待确认、正文冲突或 Redis 不可用
         */
        Optional<Queued> findQueued(Scope scope, String clientMessageId, String contentHash);

        /**
         * @param scope 已认证作用域
         * @param clientMessageId 客户端提交标识
         * @param contentHash 规范化的正文摘要
         * @param senderName 当前认证用户的显示名
         * @return 原入队结果或首次预约的单次发布许可
         * @throws RoomMessageFailure 冲突、限流、已有待确认预约或 Redis 不可用
         */
        Reservation claim(Scope scope, String clientMessageId, String contentHash, String senderName);

        /**
         * @param scope 已认证作用域
         * @param clientMessageId 客户端提交标识
         * @param reservation 已获得 Broker 确认的预约及许可
         */
        void markQueued(Scope scope, String clientMessageId, Reservation reservation);
    }

    /**
     * @param queued 预约标识及首次许可时间
     * @param leaseId 首次发布许可的标识，读取已入队预约时为空
     * @param senderName 首次预约保存的显示名，读取已入队预约时可为空
     */
    record Reservation(Queued queued, UUID leaseId, String senderName) {}

    /** 将有效的领域提交交给 Kafka。 */
    interface Producer {
        /** @param roomId 目标房间，已通过格式验证
         * @throws RoomMessageFailure 无法为目标分区建立新预约
         */
        void requireAdmission(String roomId);

        /**
         * @param submission 已校验的提交及首次许可信息
         * @return 仅在 Broker 确认后正常完成的异步结果
         * @throws RoomMessageFailure 确定拒绝生产或无法确认生产结果
         */
        CompletableFuture<Void> publish(Submission submission);
    }

    /** 查询当前作用域的终态回执。 */
    interface Receipts {
        /**
         * @param scope 已认证作用域
         * @param submissionId 公开预约标识
         * @return 仍处于查询期且已经投影的回执
         */
        Optional<Receipt> find(Scope scope, UUID submissionId);
    }
}
