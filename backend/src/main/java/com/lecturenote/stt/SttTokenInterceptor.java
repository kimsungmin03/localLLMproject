package com.lecturenote.stt;

import com.lecturenote.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class SttTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-STT-Token";

    private final byte[] expected;

    public SttTokenInterceptor(AppProperties props) {
        this.expected = props.stt().sharedSecret().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader(HEADER);
        if (token != null && MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        return false;
    }
}
