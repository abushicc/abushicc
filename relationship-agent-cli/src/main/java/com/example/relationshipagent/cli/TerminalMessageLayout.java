package com.example.relationshipagent.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * 将聊天消息转换为终端可显示的左右布局。
 *
 * <p>这里刻意只处理纯文本和显示宽度，不处理 ANSI 颜色；这样颜色控制符不会影响右对齐，
 * 也能单独对中文、换行和窄终端做单元测试。</p>
 */
final class TerminalMessageLayout {
    private static final int DEFAULT_WIDTH = 80;
    private static final int MIN_TERMINAL_WIDTH = 30;
    private static final int MAX_TERMINAL_WIDTH = 120;
    private static final int MIN_CONTENT_WIDTH = 12;

    private TerminalMessageLayout() {
    }

    static Bubble layout(String role, String content, String selfDisplayName,
                         String targetDisplayName, int terminalWidth) {
        boolean user = "USER".equalsIgnoreCase(role);
        int availableWidth = normalizeTerminalWidth(terminalWidth);
        int maxContentWidth = Math.max(MIN_CONTENT_WIDTH, availableWidth * 3 / 5 - 4);
        List<String> contentLines = wrap(content, maxContentWidth);
        int contentWidth = contentLines.stream()
                .mapToInt(TerminalMessageLayout::displayWidth)
                .max()
                .orElse(1);
        String label = user ? nonBlank(selfDisplayName, "你") : nonBlank(targetDisplayName, "对方");
        return new Bubble(user, label, availableWidth, contentWidth, List.copyOf(contentLines));
    }

    static int displayWidth(String value) {
        if (value == null || value.isEmpty()) return 0;
        int width = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            width += codePointWidth(codePoint);
            index += Character.charCount(codePoint);
        }
        return width;
    }

    private static List<String> wrap(String content, int maxWidth) {
        String source = content == null ? "" : content;
        String[] paragraphs = source.split("\\R", -1);
        List<String> lines = new ArrayList<>();
        for (String paragraph : paragraphs) {
            wrapParagraph(paragraph, maxWidth, lines);
        }
        return lines.isEmpty() ? List.of(" ") : lines;
    }

    private static void wrapParagraph(String paragraph, int maxWidth, List<String> lines) {
        if (paragraph.isEmpty()) {
            lines.add(" ");
            return;
        }

        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        for (int index = 0; index < paragraph.length(); ) {
            int codePoint = paragraph.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterWidth = codePointWidth(codePoint);
            if (currentWidth > 0 && currentWidth + characterWidth > maxWidth) {
                lines.add(current.toString());
                current.setLength(0);
                currentWidth = 0;
            }
            current.append(character);
            currentWidth += characterWidth;
            index += Character.charCount(codePoint);
        }
        if (current.isEmpty()) {
            lines.add(" ");
        } else {
            lines.add(current.toString());
        }
    }

    private static int normalizeTerminalWidth(int terminalWidth) {
        int width = terminalWidth > 0 ? terminalWidth : DEFAULT_WIDTH;
        return Math.max(MIN_TERMINAL_WIDTH, Math.min(MAX_TERMINAL_WIDTH, width));
    }

    private static int codePointWidth(int codePoint) {
        if (Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.NON_SPACING_MARK
                || Character.getType(codePoint) == Character.ENCLOSING_MARK) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    /** 覆盖常见 CJK 字符与 Emoji；终端字体差异不会破坏整体布局。 */
    private static boolean isWide(int codePoint) {
        return codePoint >= 0x1100 && (
                codePoint <= 0x115F
                        || codePoint == 0x2329 || codePoint == 0x232A
                        || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
                        || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                        || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                        || (codePoint >= 0xFE10 && codePoint <= 0xFE19)
                        || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
                        || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                        || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                        || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
                        || (codePoint >= 0x20000 && codePoint <= 0x3FFFD));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    record Bubble(boolean user, String label, int availableWidth, int contentWidth, List<String> contentLines) {
        int indent(String value) {
            return user ? Math.max(0, availableWidth - displayWidth(value)) : 0;
        }
    }
}
