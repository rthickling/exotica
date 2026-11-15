package lab;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationFilter.class);

    static String current() {
        String id = CURRENT.get();
        return id == null ? "background" : id;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        String candidate = request.getHeader("X-Correlation-ID");
        String id = candidate != null && candidate.matches("[A-Za-z0-9._:-]{1,100}") ? candidate
                : UUID.randomUUID().toString();
        CURRENT.set(id);
        MDC.put("correlationId", id);
        response.setHeader("X-Correlation-ID", id);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            LOG.info("event=http_request correlationId={} method={} path={} status={} elapsedMs={}", id,
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - start) / 1_000_000);
            CURRENT.remove();
            MDC.remove("correlationId");
        }
    }
}
