package com.tianji.gateway.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "tj.auth")
public class AuthProperties implements InitializingBean {

    private Set<String> excludePath = new HashSet<>();  // 🔴 默认初始化，避免 NPE

    @Override
    public void afterPropertiesSet() throws Exception {
        // 添加默认不拦截的路径
        if (excludePath == null) {
            excludePath = new HashSet<>();
        }
        excludePath.add("/error/**");
        excludePath.add("/jwks");
        excludePath.add("/accounts/login");
        excludePath.add("/accounts/admin/login");
        excludePath.add("/accounts/refresh");
        // 添加带网关路由前缀的登录路径（前端实际请求路径）
        excludePath.add("/as/accounts/login");
        excludePath.add("/as/accounts/admin/login");
        excludePath.add("/as/accounts/refresh");
    }
}
