package com.example.relationshipagent.companion.agent;

import java.util.List;

/**
 * Untrusted structured model response. It must pass CompanionDraftValidator before persistence.
 */
public record CompanionReplyDraft(String schemaVersion, String reply, String historyStance,
                                  List<String> usedMemoryIds, List<String> usedChunkIds,
                                  String safetyMode, List<String> limitations) {
    public static final String SCHEMA_VERSION = "companion-reply-v1";
    public static final String GROUNDED = "GROUNDED";
    public static final String NO_EVIDENCE = "NO_EVIDENCE";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String NORMAL = "NORMAL";
    public static final String SAFE_COMPLETION = "SAFE_COMPLETION";
    public static final String REFUSAL = "REFUSAL";
}
