package com.liveflow.infrastructure.kafka;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * 通过查询 broker 元数据检查应用到 Kafka 的连通性。
 * Spring 将该组件注册为 Actuator 健康检查的 {@code kafka} 项。
 */
@Component("kafka")
class KafkaHealthIndicator extends AbstractHealthIndicator {

    /** 所有健康检查共用的 Kafka 管理客户端，随该 Spring Bean 销毁而关闭。 */
    private final Admin admin;

    /**
     * 使用应用现有的 Kafka 连接配置创建健康检查客户端。
     *
     * @param kafkaAdmin Spring 管理的 Kafka 配置入口，提供 broker 地址及其他客户端属性
     * @throws org.apache.kafka.common.KafkaException Kafka 管理客户端初始化失败时抛出
     */
    KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.admin = Admin.create(kafkaAdmin.getConfigurationProperties());
    }

    /**
     * 查询 Kafka broker 列表，有可用的 broker 元数据时记为 {@code UP}，列表为空时记为 {@code DOWN}。
     * 请求失败时将异常交给 Actuator，由它将本次检查记为 {@code DOWN}。
     *
     * @param builder Actuator 传入的健康结果构建器，本方法负责写入检查状态
     * @throws java.util.concurrent.ExecutionException Kafka 的集群查询异步执行失败时抛出
     * @throws java.util.concurrent.TimeoutException 等待查询结果超过两秒时抛出
     * @throws InterruptedException 等待期间线程被中断时抛出，抛出前恢复线程的中断标记
     */
    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        try {
            // 分别限制 Kafka 请求的执行时间和健康检查线程等待结果的时间。
            var brokers = admin.describeCluster(new DescribeClusterOptions().timeoutMs(2000))
                    .nodes().get(2, TimeUnit.SECONDS);
            if (brokers.isEmpty()) {
                builder.down();
            } else {
                builder.up();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    /**
     * Spring 销毁此组件时调用，关闭 Kafka 管理客户端并释放连接和后台线程。
     * 客户端最多给未完成的 Kafka 请求两秒时间，超时后终止这些请求。
     */
    @PreDestroy
    void close() {
        admin.close(Duration.ofSeconds(2));
    }
}
