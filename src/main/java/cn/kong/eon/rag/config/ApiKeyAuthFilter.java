package cn.kong.eon.rag.config;

import cn.kong.eon.rag.config.properties.KbProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * X-API-Key 接口鉴权过滤器。
 * api-key 非空时强制校验；OPTIONS 预检请求自动放行。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";

    private final String apiKey;

    public ApiKeyAuthFilter(KbProperties kbProperties) {
        this.apiKey = kbProperties.getSecurity().getApiKey();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 未配置密钥则不启用鉴权；放行 OPTIONS 预检与健康检查/监控端点
        return apiKey == null || apiKey.isBlank()
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !apiKey.equals(provided)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"API Key missing or invalid\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
