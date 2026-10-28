package com.luigicuri.activation.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full application context backed by a PostgreSQL container. All integration tests share the same
 * configuration, so Spring caches a single context for the suite.
 *
 * <p>The scheduled worker is off: tests that exercise processing call it explicitly, which keeps them
 * deterministic and stops it from changing rows other tests are asserting on.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "activation.processing.enabled=false")
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
