package cn.keeprising.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存活探针接口：不依赖大模型或数据库，专门用来确认 Spring Boot 进程是否已经正常起来。
 */
@RestController
public class HealthController {

    // 浏览器或部署脚本访问 GET /health 时直接返回 "ok"
    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
