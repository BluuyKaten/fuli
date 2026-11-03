package com.fuli.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.nacos.api.config.ConfigService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.bootstrap.BootstrapRegistry;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NacosProfileConfigTest {
    private static final List<String> SERVICES = List.of(
            "gateway-service", "auth-service", "trade-service", "analysis-service", "data-service");
    private static final String UNREACHABLE_NACOS = "127.0.0.1:1";

    @ParameterizedTest(name = "{0}: profiles={1} skips Nacos ConfigData")
    @MethodSource("localProfiles")
    void localLoadsRealConfigWithoutResolvingNacos(String service, String profiles) {
        MockEnvironment environment = environment(service, profiles);
        DefaultBootstrapContext bootstrap = new DefaultBootstrapContext() {
            @Override
            public <T> void registerIfAbsent(Class<T> type, BootstrapRegistry.InstanceSupplier<T> supplier) {
                // The real Nacos resolver registers these properties immediately before creating its client.
                // Fail before any network access, including an optional import whose error would be swallowed.
                if (type == NacosConfigProperties.class) {
                    throw new AssertionError(service + ": local must not resolve a Nacos config import");
                }
                super.registerIfAbsent(type, supplier);
            }
        };

        ConfigDataEnvironmentPostProcessor.applyTo(environment, null, bootstrap);

        assertEquals(service, environment.getProperty("spring.application.name"));
        assertTrue(List.of(environment.getActiveProfiles()).contains("local"));
        assertEquals("false", environment.getProperty("spring.cloud.nacos.config.enabled"));
        assertEquals("false", environment.getProperty("spring.cloud.nacos.discovery.enabled"));
        assertNull(environment.getProperty("spring.config.import"));
        assertFalse(bootstrap.isRegistered(NacosConfigManager.class));
    }

    @ParameterizedTest(name = "{0}: profiles={1} retains Nacos ConfigData")
    @MethodSource("nonLocalProfiles")
    void nonLocalStillLoadsServiceConfigFromNacos(String service, String profiles) throws Exception {
        MockEnvironment environment = environment(service, profiles);
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(eq(service + ".yaml"), eq("DEFAULT_GROUP"), anyLong()))
                .thenReturn("regression.nacos.loaded: true");
        NacosConfigManager manager = mock(NacosConfigManager.class);
        when(manager.getConfigService()).thenReturn(configService);
        DefaultBootstrapContext bootstrap = new DefaultBootstrapContext();
        // Replace only the remote client: profile selection, Nacos resolver, and YAML loader remain real.
        bootstrap.register(NacosConfigManager.class, BootstrapRegistry.InstanceSupplier.of(manager));

        ConfigDataEnvironmentPostProcessor.applyTo(environment, null, bootstrap);

        assertEquals(service, environment.getProperty("spring.application.name"));
        assertEquals("optional:nacos:" + service + ".yaml?refresh=true",
                environment.getProperty("spring.config.import"));
        assertEquals("true", environment.getProperty("regression.nacos.loaded"));
        assertEquals(environment.getProperty("spring.cloud.nacos.config.server-addr"),
                bootstrap.get(NacosConfigProperties.class).getServerAddr());
        assertEquals(UNREACHABLE_NACOS,
                environment.getProperty("spring.cloud.nacos.discovery.server-addr"));
        verify(configService).getConfig(eq(service + ".yaml"), eq("DEFAULT_GROUP"), anyLong());
    }

    private static Stream<Arguments> localProfiles() {
        return SERVICES.stream().flatMap(service -> Stream.of("local", "local,smoke")
                .map(profiles -> Arguments.of(service, profiles)));
    }

    private static Stream<Arguments> nonLocalProfiles() {
        return SERVICES.stream().flatMap(service -> Stream.of("", "smoke")
                .map(profiles -> Arguments.of(service, profiles)));
    }

    private MockEnvironment environment(String service, String profiles) {
        Path repository = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        if (!Files.isDirectory(repository.resolve("gateway-service"))) {
            repository = repository.getParent();
        }
        Path resources = repository.resolve(service).resolve("src/main/resources");
        assertTrue(Files.isRegularFile(resources.resolve("application.yml")), resources.toString());
        return new MockEnvironment()
                .withProperty("spring.config.location", resources.toUri().toString())
                .withProperty("spring.profiles.active", profiles)
                .withProperty("NACOS_SERVER", UNREACHABLE_NACOS);
    }
}
