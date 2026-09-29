package com.example.clean;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GreeterTest {

    @Test
    void greet_usesName() {
        assertThat(new Greeter().greet("ada")).isEqualTo("Hello, ada");
    }
}
