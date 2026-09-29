package com.example.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GreetingControllerTest {

    @Test
    void greeting_capitalisesAndEscapesName() {
        assertThat(new GreetingController().greeting("<b>ada</b>")).isEqualTo("Hello, &lt;b&gt;ada&lt;/b&gt;");
    }
}
