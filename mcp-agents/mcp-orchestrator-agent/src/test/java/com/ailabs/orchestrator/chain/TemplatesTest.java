package com.ailabs.orchestrator.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class TemplatesTest {

    @Test
    void fillsInputPrevAndSteps() {
        List<String> out = List.of("first", "second");
        assertEquals("in=x prev=second s1=first", Templates.render("in={{input}} prev={{ prev }} s1={{step1}}", "x", out, false));
    }

    @Test
    void prevIsInputBeforeAnyStep() {
        assertEquals("go x", Templates.render("go {{prev}}", "x", List.of(), false));
    }

    @Test
    void escapesForJson() {
        String r = Templates.render("{\"msg\":\"{{prev}}\"}", "", List.of("a \"quoted\"\nline\\"), true);
        assertEquals("{\"msg\":\"a \\\"quoted\\\"\\nline\\\\\"}", r);
    }

    @Test
    void futureStepIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Templates.render("{{step3}}", "", List.of("a"), false));
    }

    @Test
    void dollarSignsAreLiteral() {
        assertEquals("cost $5", Templates.render("cost {{prev}}", "", List.of("$5"), false));
    }
}
