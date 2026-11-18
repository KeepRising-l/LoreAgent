package cn.keeprising.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LoreAgent 主启动类：整个 Spring Boot 程序的唯一启动入口。
 */
@SpringBootApplication
public class LoreAgentApplication {

    // 启动 Spring 容器并拉起内嵌 Tomcat（监听 application.yml 里配的 8080 端口）
    public static void main(String[] args) {
        SpringApplication.run(LoreAgentApplication.class, args);
    }
}
