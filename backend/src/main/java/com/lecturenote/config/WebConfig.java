package com.lecturenote.config;

import com.lecturenote.stt.SttTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;
    private final SttTokenInterceptor sttTokenInterceptor;

    public WebConfig(AppProperties props, SttTokenInterceptor sttTokenInterceptor) {
        this.props = props;
        this.sttTokenInterceptor = sttTokenInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE")
                .exposedHeaders("Location", "Content-Range", "Accept-Ranges");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sttTokenInterceptor).addPathPatterns("/internal/stt/**");
    }
}
