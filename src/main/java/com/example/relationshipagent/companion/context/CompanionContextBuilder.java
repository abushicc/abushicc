package com.example.relationshipagent.companion.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.companion.model.ChatMessage;
import com.example.relationshipagent.companion.model.ChatSession;
import com.example.relationshipagent.companion.model.CompanionTurn;
import com.example.relationshipagent.companion.repository.ChatMessageRepository;
import com.example.relationshipagent.companion.repository.CompanionTurnRepository;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.persona.model.PersonaProfile;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import com.example.relationshipagent.rag.RetrievalService;
import com.example.relationshipagent.retrieval.RetrievalChunk;
import com.example.relationshipagent.retrieval.RetrievalChunkRepository;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds one bounded context from DB rows. JVM state never decides behaviour.
 */
@Component
public class CompanionContextBuilder {
    private static final JiebaSegmenter JIEBA = new JiebaSegmenter();
    private static final Set<String> TOPIC_STOP_WORDS = Set.of("这个", "那个", "我们", "你们", "他们", "就是", "还是", "然后", "因为", "所以", "感觉", "现在", "今天", "怎么", "什么", "可以", "一下", "真的", "已经", "没有", "不是");
    private final PersonaProfileRepository personas;
    private final MemoryItemRepository memories;
    private final ChatMessageRepository messages;
    private final CompanionTurnRepository companionTurns;
    private final MessageRepository originalMessages;
    private final RetrievalChunkRepository retrievalChunks;
    private final RetrievalService retrieval;
    private final RelationshipAgentProperties properties;
    private final ObjectMapper json;

    public CompanionContextBuilder(PersonaProfileRepository personas, MemoryItemRepository memories,
                                   ChatMessageRepository messages, CompanionTurnRepository companionTurns,
                                   MessageRepository originalMessages, RetrievalChunkRepository retrievalChunks,
                                   RetrievalService retrieval, RelationshipAgentProperties properties, ObjectMapper json) {
        this.personas = personas;
        this.memories = memories;
        this.messages = messages;
        this.originalMessages = originalMessages;
        this.companionTurns = companionTurns;
        this.retrievalChunks = retrievalChunks;
        this.retrieval = retrieval;
        this.properties = properties;
        this.json = json;
    }

    public CompanionContext build(ChatSession session, ChatMessage userMessage) {
        return build(session, userMessage, false);
    }

    public CompanionContext build(ChatSession session, ChatMessage userMessage, boolean skipForSafety) {
        RelationshipAgentProperties.Companion config = properties.companion();
        // 每轮都从数据库重建上下文，确保重试、接管和多实例部署不会依赖 JVM 内存状态。
        PersonaProfile persona = personas.selectById(session.getPersonaProfileId());
        if (persona == null || !PersonaProfile.STATUS_ACTIVE.equals(persona.getStatus())
                || !session.getChatFileId().equals(persona.getChatFileId())) {
            throw new BizException(ErrorCode.COMPANION_PERSONA_UNAVAILABLE);
        }
        List<CompanionContext.MemoryView> selectedMemories = approvedMemories(session, config.maxMemories());
        // 生成的聊天历史只作为对话连续性输入；历史事实必须来自检索 chunk 或已审核 memory。
        List<ChatMessage> history = recentHistory(session.getId(), userMessage.getId(), config.recentHistoryTurns() * 2, config.maxInputChars() / 5);
        SearchDecision decision = skipForSafety
                ? new SearchDecision(false, "SKIP_SAFETY", List.of("SAFETY_GATE"), topicTerms(userMessage.getContent()))
                : decide(session, userMessage.getContent());
        // 安全拦截不触发检索；同主题复用时只回读上一轮审计过的原始 chunk，绝不把模型回复当作证据。
        List<CompanionContext.RetrievedChunk> chunks = decision.search()
                ? retrieve(session.getChatFileId(), userMessage.getContent(), decision.topicTerms(), config)
                : "SKIP_SAME_TOPIC".equals(decision.code()) ? reuseLatestChunks(session, config.maxRetrievalChunks()) : List.of();
        String resolvedDecision = decision.search() && chunks.isEmpty() ? "NO_EVIDENCE"
                : !decision.search() && "SKIP_SAME_TOPIC".equals(decision.code()) && !chunks.isEmpty() ? "REUSE" : decision.code();
        JsonNode safePersona = safePersona(persona.getProfileJson());
        List<Map<String, Object>> fewShots = fewShots(persona, session);
        // refs 和 retrievalAudit 只保存可复核的 ID、分数和哈希，不把原始 prompt 或模型输出写入数据库。
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("personaProfileId", persona.getId());
        refs.put("personaInputHash", persona.getInputHash());
        refs.put("memoryIds", selectedMemories.stream().map(CompanionContext.MemoryView::id).toList());
        refs.put("chunkIds", chunks.stream().map(CompanionContext.RetrievedChunk::chunkId).toList());
        refs.put("sourceSessionIds", chunks.stream().map(CompanionContext.RetrievedChunk::sessionId).distinct().toList());
        refs.put("recentMessageIds", history.stream().map(ChatMessage::getId).toList());
        refs.put("userMessageId", userMessage.getId());
        refs.put("retrievalDecision", resolvedDecision);
        refs.put("contextVersion", config.contextVersion());
        refs.put("promptVersion", config.promptVersion());
        refs.put("truncation", List.of());
        Map<String, Object> retrievalAudit = new LinkedHashMap<>();
        retrievalAudit.put("decision", resolvedDecision);
        retrievalAudit.put("reasons", decision.reasons());
        retrievalAudit.put("queryHashes", decision.search() ? queryHashes(retrievalQueries(userMessage.getContent(), decision.topicTerms())) : List.of());
        retrievalAudit.put("usedChunks", chunks.stream().map(chunk -> Map.of("chunkId", chunk.chunkId(), "sessionId", chunk.sessionId(), "score", chunk.score(), "channel", chunk.channel())).toList());
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("session", session.getId());
        identity.put("persona", List.of(persona.getId(), nullToEmpty(persona.getInputHash()), persona.getUpdatedAt()));
        identity.put("memory", selectedMemories.stream().map(memory -> List.of(memory.id(), nullToEmpty(memory.inputHash()))).toList());
        identity.put("chunks", chunks.stream().map(chunk -> chunk.chunkId()).toList());
        identity.put("history", history.stream().map(message -> List.of(message.getId(), sha256(message.getContent()))).toList());
        identity.put("user", List.of(userMessage.getId(), sha256(userMessage.getContent())));
        identity.put("decision", resolvedDecision);
        identity.put("versions", List.of(config.contextVersion(), config.promptVersion(), config.safetyVersion(), config.model(), config.reasoningEffort(), config.store()));
        return new CompanionContext(session, persona, userMessage, history, selectedMemories, chunks, fewShots, safePersona,
                resolvedDecision, decision.reasons(), decision.topicTerms(), sha256(write(identity)), write(refs), write(retrievalAudit));
    }

    private List<CompanionContext.MemoryView> approvedMemories(ChatSession session, int limit) {
        // 只有 ACTIVE + APPROVED 的 memory 可以进入模型上下文，并受字符预算限制。
        List<MemoryItem> rows = memories.selectList(new LambdaQueryWrapper<MemoryItem>()
                .eq(MemoryItem::getChatFileId, session.getChatFileId()).eq(MemoryItem::getTargetPerson, session.getTargetPerson())
                .eq(MemoryItem::getStatus, MemoryItem.STATUS_ACTIVE).eq(MemoryItem::getReviewStatus, MemoryItem.REVIEW_APPROVED)
                .orderByDesc(MemoryItem::getConfidence).orderByDesc(MemoryItem::getUpdatedAt).last("LIMIT " + Math.max(1, limit)));
        int budget = 4000;
        List<CompanionContext.MemoryView> result = new ArrayList<>();
        for (MemoryItem row : rows) {
            String content = trim(row.getContent(), Math.min(900, budget));
            if (content.isBlank()) continue;
            result.add(new CompanionContext.MemoryView(row.getId(), row.getMemoryType(), content, row.getInputHash()));
            budget -= content.length();
            if (budget <= 0) break;
        }
        return List.copyOf(result);
    }

    private List<ChatMessage> recentHistory(String sessionId, String currentMessageId, int limit, int budget) {
        List<ChatMessage> rows = messages.selectList(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getChatSessionId, sessionId)
                .ne(ChatMessage::getId, currentMessageId).in(ChatMessage::getRole, ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT)
                .orderByDesc(ChatMessage::getCreatedAt).last("LIMIT " + Math.max(1, limit)));
        rows.sort(Comparator.comparing(ChatMessage::getCreatedAt));
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage row : rows) {
            if (row.getContent() == null) continue;
            budget -= row.getContent().length();
            if (budget < 0 && result.size() >= 8) break;
            result.add(row);
        }
        return List.copyOf(result);
    }

    private SearchDecision decide(ChatSession session, String content) {
        String topic = topicTerms(content);
        String stripped = content == null ? "" : content.trim();
        if (isGreeting(stripped)) return new SearchDecision(false, "SKIP_GREETING", List.of("GREETING"), topic);
        boolean explicit = containsAny(stripped, "以前", "那时候", "还记得", "当时", "过去", "那次");
        boolean first = session.getLastSearchAt() == null;
        boolean drift = isTopicDrift(topic, session.getTopicTerms());
        boolean refresh = drift && session.getTurnsSinceLastSearch() != null && session.getTurnsSinceLastSearch() > properties.companion().refreshSearchAfterTurns();
        if (explicit || first || drift || refresh)
            return new SearchDecision(true, "SEARCH", List.of(explicit ? "EXPLICIT_HISTORY" : first ? "FIRST_SUBSTANTIVE" : "TOPIC_SHIFT"), topic);
        return new SearchDecision(false, "SKIP_SAME_TOPIC", List.of("SAME_TOPIC"), topic);
    }

    /**
     * Reconstructs only previously audited source chunks; generated conversation is never a retrieval input.
     */
    private List<CompanionContext.RetrievedChunk> reuseLatestChunks(ChatSession session, int limit) {
        // 复用来源限定为上一轮 SUCCESS turn 的 usedChunks，并再次校验 chatFile，防止跨文件污染。
        CompanionTurn latest = companionTurns.selectOne(new LambdaQueryWrapper<CompanionTurn>()
                .eq(CompanionTurn::getChatSessionId, session.getId()).eq(CompanionTurn::getStatus, CompanionTurn.STATUS_SUCCESS)
                .isNotNull(CompanionTurn::getRetrievalJson).orderByDesc(CompanionTurn::getFinishedAt).last("LIMIT 1"));
        if (latest == null || latest.getRetrievalJson() == null) return List.of();
        try {
            JsonNode used = json.readTree(latest.getRetrievalJson()).path("usedChunks");
            if (!used.isArray()) return List.of();
            List<String> ids = new ArrayList<>();
            Map<String, JsonNode> auditById = new LinkedHashMap<>();
            for (JsonNode item : used) {
                String id = item.path("chunkId").asText("");
                if (!id.isBlank() && ids.size() < limit) {
                    ids.add(id);
                    auditById.put(id, item);
                }
            }
            if (ids.isEmpty()) return List.of();
            Map<String, RetrievalChunk> byId = new LinkedHashMap<>();
            for (RetrievalChunk chunk : retrievalChunks.selectBatchIds(ids))
                if (session.getChatFileId().equals(chunk.getChatFileId())) byId.put(chunk.getId(), chunk);
            List<CompanionContext.RetrievedChunk> result = new ArrayList<>();
            for (String id : ids) {
                RetrievalChunk chunk = byId.get(id);
                JsonNode audit = auditById.get(id);
                if (chunk == null) continue;
                result.add(new CompanionContext.RetrievedChunk(id, chunk.getParentSessionId(), trim(chunk.getRetrievalText(), 3000), audit.path("score").asDouble(0), audit.path("channel").asText("REUSE")));
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<CompanionContext.RetrievedChunk> retrieve(String chatFileId, String userQuery, String topic, RelationshipAgentProperties.Companion config) {
        LinkedHashSet<String> queries = retrievalQueries(userQuery, topic);
        List<List<CompanionContext.RetrievedChunk>> candidatesByQuery = new ArrayList<>();
        boolean hasIntentExpansion = !eventIntentQuery(userQuery).isBlank();
        int queryCount = 0;
        for (String query : queries) {
            if (queryCount++ >= config.maxRetrievalQueries()) break;
            RetrievalService.EvidenceRetrievalResponse response = retrieval.retrieveEvidence(chatFileId,
                    new RetrievalService.EvidenceRetrievalRequest(query, null, null, null, null, config.maxRetrievalChunks(), "score"));
            if (!response.answerable()) continue;
            List<CompanionContext.RetrievedChunk> candidates = new ArrayList<>();
            for (RetrievalService.EvidenceSession session : response.sessions()) {
                for (RetrievalService.EvidenceChunk chunk : session.chunks()) {
                    candidates.add(new CompanionContext.RetrievedChunk(chunk.chunkId(), chunk.sessionId(), trim(chunk.retrievalText(), 3000), chunk.score(), chunk.retrievalChannel()));
                    break;
                }
            }
            if (!candidates.isEmpty()) candidatesByQuery.add(candidates);
        }
        // One broad query must not exhaust the small evidence budget before a more specific
        // intent expansion is considered. Round-robin preserves query diversity and session deduplication.
        List<CompanionContext.RetrievedChunk> result = new ArrayList<>();
        Set<String> seenSessions = new LinkedHashSet<>();
        // A known narrow intent can legitimately need its second ranked session (for example,
        // an internship query whose strongest exact match is not rank one). Reserve one extra
        // slot for it before broad wording and topic fragments consume the three-item budget.
        if (hasIntentExpansion && !candidatesByQuery.isEmpty()) {
            for (CompanionContext.RetrievedChunk candidate : candidatesByQuery.get(0)) {
                if (seenSessions.add(candidate.sessionId())) result.add(candidate);
                if (result.size() >= Math.min(2, config.maxRetrievalChunks())) break;
            }
        }
        for (int rank = 0; result.size() < config.maxRetrievalChunks(); rank++) {
            boolean found = false;
            for (List<CompanionContext.RetrievedChunk> candidates : candidatesByQuery) {
                if (rank >= candidates.size()) continue;
                CompanionContext.RetrievedChunk candidate = candidates.get(rank);
                if (seenSessions.add(candidate.sessionId())) {
                    result.add(candidate);
                    found = true;
                }
                if (result.size() >= config.maxRetrievalChunks()) break;
            }
            boolean remaining = false;
            for (List<CompanionContext.RetrievedChunk> candidates : candidatesByQuery) {
                if (rank < candidates.size()) {
                    remaining = true;
                    break;
                }
            }
            if (!found && !remaining) break;
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> fewShots(PersonaProfile persona, ChatSession session) {
        // Persona 只保存 few-shot 的消息 ID；这里回读原始 message 并做文件、speaker、内容完整性校验。
        try {
            JsonNode examples = json.readTree(persona.getProfileJson()).path("fewShotExamples");
            if (!examples.isArray()) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode example : examples) {
                if (result.size() >= 3) break;
                List<String> contextIds = json.convertValue(example.path("contextMessageIds"), new TypeReference<List<String>>() {
                });
                List<String> targetIds = json.convertValue(example.path("targetMessageIds"), new TypeReference<List<String>>() {
                });
                Set<String> ids = new LinkedHashSet<>();
                ids.addAll(contextIds);
                ids.addAll(targetIds);
                if (ids.isEmpty()) continue;
                Map<String, Message> byId = new LinkedHashMap<>();
                for (Message message : originalMessages.selectBatchIds(ids)) byId.put(message.getId(), message);
                if (byId.size() != ids.size() || targetIds.stream().anyMatch(id -> byId.get(id) == null || !session.getTargetPerson().equals(byId.get(id).getSpeaker())))
                    continue;
                List<Map<String, String>> context = new ArrayList<>();
                for (String id : contextIds) {
                    Message message = byId.get(id);
                    if (!usable(message, session.getChatFileId())) {
                        context.clear();
                        break;
                    }
                    context.add(Map.of("speaker", message.getSpeaker(), "content", trim(message.getCleanedContent(), 600)));
                }
                if (contextIds.size() > 0 && context.isEmpty()) continue;
                List<String> replies = new ArrayList<>();
                for (String id : targetIds) {
                    Message message = byId.get(id);
                    if (!usable(message, session.getChatFileId())) {
                        replies.clear();
                        break;
                    }
                    replies.add(trim(message.getCleanedContent(), 600));
                }
                if (replies.isEmpty()) continue;
                result.add(Map.of("context", context, "targetReplies", replies));
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private JsonNode safePersona(String profileJson) {
        // 仅暴露风格特征、安全边界和覆盖度，避免把 Persona 构建过程中的原始输入泄露给模型。
        try {
            JsonNode source = json.readTree(profileJson);
            ObjectNode out = json.createObjectNode();
            out.set("features", source.path("features"));
            out.set("safetyBoundaries", source.path("safetyBoundaries"));
            out.set("coverage", source.path("coverage"));
            return out;
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static LinkedHashSet<String> retrievalQueries(String userQuery, String topic) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String intent = eventIntentQuery(userQuery);
        if (!intent.isBlank()) queries.add(intent);
        if (userQuery != null && !userQuery.isBlank()) queries.add(userQuery);
        if (topic != null && !topic.isBlank()) queries.add(topic);
        return queries;
    }

    static String eventIntentQuery(String content) {
        String text = content == null ? "" : content;
        if (containsAny(text, "论文格式", "改论文", "论文被改", "论文的人气")) return "论文代改 拖了 pua 大吵一架";
        if (containsAny(text, "晚安", "没睡", "还没睡")) return "晚安 虚伪 玩手机";
        if (containsAny(text, "口头禅", "语气词", "哦呦", "好嘞")) return "哦呦";
        if (containsAny(text, "关心", "体贴")) return "吃饭 回到家 走路 恭喜";
        if (containsAny(text, "实习", "工作压力", "上班压力")) return "实习";
        if (containsAny(text, "生气", "不满", "怎么表达")) return "大吵一架";
        if (containsAny(text, "吵架", "争吵", "矛盾", "生气", "冲突")) return "争吵 矛盾";
        if (containsAny(text, "和好", "复合", "原谅", "道歉")) return "和好 道歉";
        return "";
    }

    private static List<String> queryHashes(Iterable<String> queries) {
        List<String> hashes = new ArrayList<>();
        for (String query : queries) hashes.add(sha256(query));
        return hashes;
    }

    private static boolean usable(Message message, String chatFileId) {
        return message != null && chatFileId.equals(message.getChatFileId()) && message.getCleanedContent() != null && !message.getCleanedContent().isBlank();
    }

    private static boolean isGreeting(String text) {
        return text.length() <= 8 && containsAny(text.toLowerCase(Locale.ROOT), "你好", "嗨", "哈喽", "在吗", "晚安", "早", "早安", "晚") && !text.contains("以前");
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private static boolean isTopicDrift(String current, String previous) {
        Set<String> now = topicSet(current), before = topicSet(previous);
        if (now.size() < 2 || before.size() < 2) return false;
        Set<String> overlap = new LinkedHashSet<>(now);
        overlap.retainAll(before);
        Set<String> union = new LinkedHashSet<>(now);
        union.addAll(before);
        Set<String> newTerms = new LinkedHashSet<>(now);
        newTerms.removeAll(before);
        return (double) overlap.size() / union.size() < 0.2d && newTerms.size() >= 2;
    }

    private static Set<String> topicSet(String terms) {
        if (terms == null || terms.isBlank()) return Set.of();
        return new LinkedHashSet<>(List.of(terms.split("\\s+")));
    }

    private static String topicTerms(String content) {
        if (content == null || content.isBlank()) return "";
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (SegToken token : JIEBA.process(content, JiebaSegmenter.SegMode.SEARCH)) {
            String word = token.word.trim();
            if (word.length() >= 2 && word.length() <= 12 && !TOPIC_STOP_WORDS.contains(word)) terms.add(word);
            if (terms.size() >= 8) break;
        }
        return String.join(" ", terms);
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize Companion context", e);
        }
    }

    public static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record SearchDecision(boolean search, String code, List<String> reasons, String topicTerms) {
    }
}
