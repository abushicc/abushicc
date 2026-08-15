package com.example.relationshipagent.companion.safety;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * High-risk paths intentionally leave Persona style and never invoke the model.
 */
@Component
public class CompanionSafetyGate {
    public Decision evaluate(String userMessage) {
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (contains(text, "替我发", "替我回复", "帮我联系", "给他发", "给她发", "联系他", "联系她")) {
            return new Decision(true, "REFUSAL", "我不能冒充任何人去联系或回复第三方。", "THIRD_PARTY_IMPERSONATION");
        }
        if (contains(text, "你是真人", "你是本人", "你就是她", "你有真实意识")) {
            return new Decision(true, "SAFE_COMPLETION", "我是基于历史文本的风格化模拟，不是真实本人。", "IDENTITY_BOUNDARY");
        }
        if (contains(text, "自杀", "自残", "杀了自己", "伤害自己", "杀人", "伤害别人")) {
            return new Decision(true, "SAFE_COMPLETION", "如果你或他人现在有危险，请立刻联系当地紧急服务、可信任的人或专业危机支持。", "IMMINENT_HARM");
        }
        return new Decision(false, "NORMAL", null, null);
    }

    private static boolean contains(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    public record Decision(boolean handled, String safetyMode, String reply, String ruleCode) {
    }
}
