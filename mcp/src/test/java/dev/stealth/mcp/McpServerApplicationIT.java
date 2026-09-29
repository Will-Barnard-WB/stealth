package dev.stealth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class McpServerApplicationIT {

    @Autowired private McpSyncServer server;

    @Test
    void serverInfo_defaultConfig_identifiesAsStealth() {
        assertThat(server.getServerInfo().name()).isEqualTo("stealth");
    }
}
