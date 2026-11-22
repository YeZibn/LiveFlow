package com.liveflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LiveFlow 后端的 Spring Boot 应用入口。
 * 从 {@code com.liveflow} 扫描 API、业务服务和基础设施组件，再按启用的 profile 加载配置。
 */
@SpringBootApplication
public class LiveFlowApplication {

    /**
     * 将命令行参数交给 Spring Boot，创建应用上下文并启动内嵌 Web 服务器。
     *
     * @param args 启动参数，例如 {@code --spring.profiles.active=local}，可以是空数组
     */
    public static void main(String[] args) {
        SpringApplication.run(LiveFlowApplication.class, args);
    }
}
