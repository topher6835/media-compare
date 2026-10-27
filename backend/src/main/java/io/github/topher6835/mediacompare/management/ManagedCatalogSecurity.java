package io.github.topher6835.mediacompare.management;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/** Installed only by the private managed entry point, before the web server accepts requests. */
final class ManagedCatalogSecurity {
    static final String HEADER = "X-Media-Compare-Child";
    private ManagedCatalogSecurity() { }

    static FilterRegistrationBean<Filter> registration(String credential) {
        Filter filter = (request, response, chain) -> {
            var http = (HttpServletRequest) request;
            String supplied = http.getHeader(HEADER);
            if (supplied == null || !MessageDigest.isEqual(credential.getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8)) || http.getHeader("Origin") != null
                    || !("127.0.0.1:" + http.getLocalPort()).equals(http.getHeader("Host"))) {
                ((HttpServletResponse) response).setStatus(403);
                return;
            }
            chain.doFilter(request, response);
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Integer.MIN_VALUE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
