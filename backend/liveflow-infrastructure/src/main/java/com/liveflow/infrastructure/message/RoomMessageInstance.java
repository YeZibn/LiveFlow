package com.liveflow.infrastructure.message;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本机身份及整个环境一致的短期接入判断预算。 */
@Component
record RoomMessageInstance(String id, List<String> members, String bootId,
        Duration freshness, Duration handoff, Duration backlogLimit) {

    /** @param id 进程标识
     * @param memberList 所有接入实例，空值表示本地单实例
     * @param freshness 快照有效期
     * @param handoff 在有效期内允许失去活动分区后的交接时间
     * @param backlogLimit 触发新提交保护的最老积压年龄
     */
    @org.springframework.beans.factory.annotation.Autowired
    RoomMessageInstance(@Value("${liveflow.instance-id:local}") String id,
            @Value("${liveflow.cluster-member-ids:}") String memberList,
            @Value("${liveflow.room-messages.v2.snapshot-freshness:12s}") Duration freshness,
            @Value("${liveflow.room-messages.v2.handoff-grace:8s}") Duration handoff,
            @Value("${liveflow.room-messages.v2.backlog-limit:2m}") Duration backlogLimit) {
        this(id, memberList.isBlank() ? List.of(id) : Arrays.stream(memberList.split(",", -1))
                .map(String::strip).toList(), UUID.randomUUID().toString(), freshness, handoff, backlogLimit);
        if (!members.contains(id) || members.stream().anyMatch(member -> !member.matches("[A-Za-z0-9_-]{1,64}"))
                || members.stream().distinct().count() != members.size()
                || freshness.compareTo(Duration.ofSeconds(2)) <= 0 || handoff.isNegative()
                || handoff.compareTo(freshness) > 0 || !backlogLimit.isPositive()
                || backlogLimit.plus(freshness).compareTo(Duration.ofMinutes(12)) >= 0) {
            throw new IllegalArgumentException("弹幕实例或运行状态配置无效");
        }
    }
}
