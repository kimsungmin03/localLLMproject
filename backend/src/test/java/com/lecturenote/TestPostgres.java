package com.lecturenote;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 테스트 JVM 당 한 번 띄우는 임베디드 PostgreSQL. JVM 종료 시 함께 내려간다. */
public final class TestPostgres {

    private static final EmbeddedPostgres POSTGRES = start();

    private TestPostgres() {}

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                }
            }));
            return pg;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start embedded PostgreSQL", e);
        }
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
