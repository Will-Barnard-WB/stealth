package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootTest
class StealthApplicationTest {

    @Autowired private ConfigurableApplicationContext context;

    @Test
    void context_cliCommands_haveNoMcpServerBeans() {
        assertThat(
                        Arrays.stream(context.getBeanDefinitionNames())
                                .map(name -> context.getBeanFactory().getBeanDefinition(name))
                                .map(d -> d.getBeanClassName())
                                .filter(Objects::nonNull))
                .noneMatch(name -> name.startsWith("org.springframework.ai."));
        assertThat(context.getBeanNamesForType(io.modelcontextprotocol.server.McpSyncServer.class))
                .isEmpty();
    }
}
