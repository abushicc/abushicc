package com.example.relationshipagent.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMessageLayoutTest {

    @Test
    void rendersTargetPersonOnTheLeft() {
        TerminalMessageLayout.Bubble bubble = TerminalMessageLayout.layout(
                "ASSISTANT", "我在呢", "阿布", "耳朵小", 80);

        assertFalse(bubble.user());
        assertEquals("耳朵小", bubble.label());
        assertEquals(0, bubble.indent(bubble.label()));
        assertEquals(0, bubble.indent("我在呢"));
    }

    @Test
    void rendersUserOnTheRight() {
        TerminalMessageLayout.Bubble bubble = TerminalMessageLayout.layout(
                "USER", "晚上好", "阿布", "耳朵小", 80);

        assertTrue(bubble.user());
        assertEquals("阿布", bubble.label());
        assertEquals(76, bubble.indent(bubble.label()));
        assertEquals(74, bubble.indent("晚上好"));
        assertEquals(80, bubble.indent("晚上好") + TerminalMessageLayout.displayWidth("晚上好"));
    }

    @Test
    void wrapsLongChineseTextByDisplayWidth() {
        TerminalMessageLayout.Bubble bubble = TerminalMessageLayout.layout(
                "ASSISTANT", "这是一个用于验证中文终端显示宽度的很长消息", "阿布", "耳朵小", 30);

        assertTrue(bubble.contentLines().size() > 1);
        assertTrue(bubble.contentLines().stream()
                .allMatch(line -> TerminalMessageLayout.displayWidth(line) <= 14));
    }

    @Test
    void preservesExplicitLineBreaksAndSupportsNarrowTerminal() {
        TerminalMessageLayout.Bubble bubble = TerminalMessageLayout.layout(
                "USER", "第一行\n第二行", "阿布", "耳朵小", 8);

        assertEquals(List.of("第一行", "第二行"), bubble.contentLines());
        assertTrue(bubble.indent("第二行") >= 0);
        assertTrue(bubble.contentWidth() >= 1);
    }
}
