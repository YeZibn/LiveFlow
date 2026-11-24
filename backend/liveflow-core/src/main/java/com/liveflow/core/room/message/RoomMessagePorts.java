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

    /** 管理账号额度、预约及生产租约。 */
    interface Admission {
        /**
         * @param scope 已认证作用域
         * @param clientMessageId 客户端提交标识
         * @param contentHash 已规范化正文的摘要
         * @return 已确认入队的原预约，尚未确认时为空
         * @throws RoomMessageFailure 预约正文冲突
         */
        Optional<Queued> findQueued(Scope scope, String clientMessageId, String contentHash);

        /**
         * @param scope 已认证作用域
         * @param clientMessageId 客户端提交标识
         * @param contentHash 规范化的正文摘要
         * @param senderName 当前认证用户的显示名
         * @return 原入队结果或授予本次发布的租约
         * @throws RoomMessageFailure 冲突、限流、过期或已有有效租约
         */
        Reservation claim(Scope scope, String clientMessageId, String contentHash, String senderName);

        /**
         * @param scope 已认证作用域
         * @param clientMessageId 客户端提交标识
         * @param reservation 已获得 Broker 确认的预约及租约
         */
        void markQueued(Scope scope, String clientMessageId, Reservation reservation);
    }

    /** 无租约表示预约早已收到 Broker 确认。 */
    record Reservation(Queued queued, UUID leaseId, String senderName) {}

    /** 将有效的领域提交交给 Kafka。 */
    interface Producer {
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
