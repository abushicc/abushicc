package com.example.relationshipagent.rag;

import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.service.ChatFileService;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.retrieval.RetrievalChunk;
import com.example.relationshipagent.retrieval.RetrievalChunkRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 混合检索服务（阶段 2 M5）：向量路 + 关键词路 → RRF 融合 → 按父会话聚合。
 *
 * <p>编码先行降级设计（0.5 决策 6）：
 * <ul>
 *   <li>chatFile.status = READY 且 EmbeddingModel 可用 → hybrid（向量 + 关键词）</li>
 *   <li>否则 → keywordOnly（仅关键词 + 结构化过滤），响应 mode 字段标注</li>
 * </ul>
 *
 * <p>RRF 融合（0.5 决策 4）：rrf(d) = Σ 1/(60 + rank_i(d))，两路合并去重排序。
 * 关键词路使用 ILIKE 多词匹配，结构化过滤（speaker/time/sessionType）在 SQL WHERE 层前置生效。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final int VECTOR_TOP_N_MULTIPLIER = 4;
    private static final int MAX_VECTOR_TOP_N = 50;
    private static final int KEYWORD_TOP_N = 50;
    private static final int MIN_KW_LENGTH = 2;
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})年");
    private static final Pattern MONTH_PATTERN = Pattern.compile("(20\\d{2})年\\s*(1[0-2]|[1-9])月");
    private static final Pattern QUOTED_PHRASE_PATTERN = Pattern.compile("[‘'“\"]([^’'”\"]{2,30})[’'”\"]");
    private static final Pattern EVENT_SUBJECT_PATTERN = Pattern.compile(
            "(?:聊起|聊到|关于)([\\p{IsHan}]{2,12}?)(?:的那次|那次|的讨论|讨论|$)");
    private static final Pattern SHORT_HAN_PHRASE_PATTERN = Pattern.compile("[\\p{IsHan}]{2,4}");
    private static final com.huaban.analysis.jieba.JiebaSegmenter JIEBA =
            new com.huaban.analysis.jieba.JiebaSegmenter();

    /**
     * 简易停用词（M6 完整版在 StatisticsService 用 jieba）
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
            "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
            "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "什么",
            "怎么", "为什么", "因为", "所以", "但是", "如果", "然后", "可以", "还是",
            "这个", "那个", "吗", "吧", "啊", "呢", "哦", "嗯", "我们", "时候",
            "一般", "哪些", "哪个", "各自", "是否", "一下", "或者", "以及");

    /**
     * 只描述问法、不能单独证明语料存在对应事实的词。
     */
    private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
            "喜欢", "平时", "事情", "对话", "聊天", "记录", "讨论", "节目",
            "那次", "聊起", "聊到", "主动", "帮忙", "什么", "哪些", "时候");

    private final ChatFileService chatFileService;
    private final RetrievalChunkRepository chunkRepository;
    private final ConversationSessionRepository sessionRepository;
    private final RelationshipAgentProperties properties;
    private final ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModelProvider;
    private final ConcurrentMap<String, Double> idfCache = new ConcurrentHashMap<>();

    public RetrievalService(ChatFileService chatFileService,
                            RetrievalChunkRepository chunkRepository,
                            ConversationSessionRepository sessionRepository,
                            RelationshipAgentProperties properties,
                            ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModelProvider) {
        this.chatFileService = chatFileService;
        this.chunkRepository = chunkRepository;
        this.sessionRepository = sessionRepository;
        this.properties = properties;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /**
     * 检索入口
     */
    public SearchResponse search(String chatFileId, SearchRequest req) {
        ChatFile cf = chatFileService.getById(chatFileId);
        if (!List.of(ChatFile.STATUS_CHUNKED, ChatFile.STATUS_READY).contains(cf.getStatus())) {
            throw new BizException(ErrorCode.CHAT_FILE_NOT_READY);
        }

        QueryPlan plan = buildQueryPlan(cf, req);
        // “第一次/最后一次/冲突高峰”等问题需要对全量会话排序，不能把向量相似度当作时间事实。
        if (plan.structured()) {
            return structuredSearch(chatFileId, req, plan);
        }

        int topK = req.resolvedTopK();
        int vectorTopN = Math.min(topK * VECTOR_TOP_N_MULTIPLIER, MAX_VECTOR_TOP_N);

        // 模式判定
        boolean hybrid = cf.getStatus().equals(ChatFile.STATUS_READY)
                && embeddingModelProvider.getIfAvailable() != null;

        // 1. 结构化过滤参数
        Instant st = plan.startTime();
        Instant et = plan.endTime();
        String stype = plan.sessionType();
        String spk = req.speaker();

        // 2. 关键词提取：保留短语和事件主题作为词法锚点，后续用于解释命中和拒答。
        List<String> quotedPhrases = extractQuotedPhrases(req.query());
        List<String> keywords = new ArrayList<>(extractKeywords(req.query()));
        for (String phrase : quotedPhrases) addKeyword(keywords, phrase);
        for (String subject : extractEventSubjects(req.query())) addKeyword(keywords, subject);
        expandEventKeywords(req.query(), keywords);
        keywords = compactKeywords(keywords);
        List<String> informativeKeywords = keywords.stream()
                .filter(kw -> !GENERIC_QUERY_TERMS.contains(kw))
                .toList();
        Map<String, Double> keywordScores = new HashMap<>();
        Map<String, Integer> anchorHitCounts = new HashMap<>();

        // 3. 向量路（仅 hybrid 模式）。向量负责语义扩展，但不能单独证明问题中的具体事实。
        Set<String> vectorChunkIds = new LinkedHashSet<>();
        Map<String, Double> vectorScores = new HashMap<>();
        boolean vectorDistanceAvailable = false;
        Map<String, RetrievalChunk> chunksById = new LinkedHashMap<>();
        if (hybrid) {
            try {
                var model = embeddingModelProvider.getObject();
                float[] qv = model.embed(req.query());
                String qvText = EmbeddingBatchWriter.toVectorText(qv);
                List<RetrievalChunk> vecResults = chunkRepository.vectorSearch(
                        chatFileId, qvText, properties.embedding().model(),
                        st, et, stype, spk, vectorTopN);
                for (RetrievalChunk chunk : vecResults) {
                    Double distance = chunk.getSearchDistance();
                    // 距离字段存在即说明本次 pgvector 查询可用于置信度判断，
                    // 即使该候选随后因超过阈值被过滤，也不能把它当成“无距离信息”。
                    if (distance != null) {
                        vectorDistanceAvailable = true;
                    }
                    if (distance != null && distance > retrievalConfig().maxVectorDistance()) {
                        continue;
                    }
                    vectorChunkIds.add(chunk.getId());
                    if (distance != null) {
                        vectorScores.put(chunk.getId(), Math.max(0.0, 1.0 - distance));
                    } else {
                        // 测试替身/非 pgvector 数据库可能没有 distance；保留候选，生产查询始终有距离。
                        vectorScores.put(chunk.getId(), 1.0 / vectorChunkIds.size());
                    }
                    chunksById.put(chunk.getId(), chunk);
                }
            } catch (Exception e) {
                log.warn("Vector search failed, falling back to keywordOnly: {}", e.getMessage());
                hybrid = false;
            }
        }

        // 4. 关键词路：用 IDF 提高稀有词权重，用短语覆盖率衡量候选是否真的提到问题主题。
        Set<String> keywordChunkIds = new LinkedHashSet<>();
        if (!keywords.isEmpty()) {
            List<RetrievalChunk> kwResults = chunkRepository.keywordSearch(
                    chatFileId, keywords, st, et, stype, spk, KEYWORD_TOP_N);
            Map<String, Double> idfs = new LinkedHashMap<>();
            for (String kw : keywords) idfs.put(kw, keywordIdf(chatFileId, kw));
            double totalWeight = idfs.values().stream().mapToDouble(Double::doubleValue).sum();
            for (RetrievalChunk c : kwResults) {
                String text = c.getRetrievalText() != null ? c.getRetrievalText() : "";
                String lowerText = text.toLowerCase(Locale.ROOT);
                double matchedWeight = 0.0;
                for (String kw : keywords) {
                    if (lowerText.contains(kw.toLowerCase(Locale.ROOT))) {
                        matchedWeight += idfs.getOrDefault(kw, 1.0);
                    }
                }
                int anchorHits = 0;
                for (String kw : informativeKeywords) {
                    if (lowerText.contains(kw.toLowerCase(Locale.ROOT))) anchorHits++;
                }
                double phraseBonus = quotedPhrases.stream()
                        .anyMatch(p -> lowerText.contains(p.toLowerCase(Locale.ROOT))) ? 0.25 : 0.0;
                if (matchedWeight > 0) {
                    keywordChunkIds.add(c.getId());
                    double coverage = matchedWeight / Math.max(totalWeight, 1.0);
                    keywordScores.put(c.getId(), Math.min(1.0, coverage + phraseBonus));
                    anchorHitCounts.put(c.getId(), anchorHits);
                    chunksById.put(c.getId(), c);
                }
            }
        }

        // 5. 可解释加权融合：关键词覆盖与稀有度为主，向量补充语义召回；双路一致时加分。
        Set<String> candidateIds = new LinkedHashSet<>(vectorChunkIds);
        candidateIds.addAll(keywordChunkIds);
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        for (String id : candidateIds) {
            double keywordScore = keywordScores.getOrDefault(id, 0.0);
            double vectorScore = vectorScores.getOrDefault(id, 0.0);
            double score;
            if (hybrid) {
                score = 0.55 * keywordScore + 0.45 * vectorScore;
                if (keywordScore > 0 && vectorScore > 0) score += 0.10;
            } else {
                score = keywordScore;
            }
            // In top-K evidence retrieval, an actual non-generic lexical anchor must not be
            // displaced entirely by broad vector neighbours. This is a ranking preference,
            // not an answerability bypass: candidates still need a real keyword match.
            if (anchorHitCounts.getOrDefault(id, 0) > 0) score += 0.30;
            fusedScores.put(id, Math.min(score, 1.0));
        }

        // 6. 先融合全部候选，再按父会话聚合；最后才限制 topK session，避免同一会话的多个 chunk 挤占名额。
        List<Map.Entry<String, Double>> sorted = fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        // 7. 按父会话聚合
        Map<String, SessionHit> sessionMap = new LinkedHashMap<>();
        for (var entry : sorted) {
            RetrievalChunk chunk = chunksById.get(entry.getKey());
            if (chunk == null) {
                chunk = chunkRepository.selectById(entry.getKey());
            }
            if (chunk == null) continue;
            String sid = chunk.getParentSessionId();
            SessionHit sh = sessionMap.computeIfAbsent(sid, k -> {
                ConversationSession s = sessionRepository.selectById(sid);
                return new SessionHit(s != null ? s.getId() : sid,
                        s != null ? s.getStartTime() : null,
                        s != null ? s.getEndTime() : null,
                        s != null ? s.getMessageCount() : 0,
                        s != null ? s.getSessionType() : null,
                        s != null ? s.getSummary() : null,
                        s != null ? s.getFormattedText() : null,
                        0.0, new ArrayList<>());
            });
            sh.score = Math.max(sh.score, entry.getValue());
            if (sh.hits.size() < 2) {
                sh.hits.add(new ChunkHit(chunk.getId(), chunk.getSequenceNo(), chunk.getRetrievalText()));
            }
        }

        // 8. 默认按融合分数排序；明确的时间排序只影响已召回结果，结构化时间问题已在前面单独处理。
        List<SessionHit> sessions = new ArrayList<>(sessionMap.values());
        switch (req.resolvedSort()) {
            case "timeAsc" ->
                    sessions.sort(Comparator.comparing(s -> s.startTime != null ? s.startTime : Instant.EPOCH));
            case "timeDesc" -> sessions.sort((a, b) -> {
                Instant ta = a.startTime != null ? a.startTime : Instant.EPOCH;
                Instant tb = b.startTime != null ? b.startTime : Instant.EPOCH;
                return tb.compareTo(ta);
            });
            default -> sessions.sort((a, b) -> Double.compare(b.score, a.score));
        }

        List<SessionHit> topSessions = sessions.stream().limit(topK).toList();
        Set<String> topSessionIds = topSessions.stream().map(s -> s.sessionId).collect(java.util.stream.Collectors.toSet());
        boolean lexicalEvidence = sorted.stream()
                .filter(entry -> {
                    RetrievalChunk chunk = chunksById.get(entry.getKey());
                    return chunk != null && topSessionIds.contains(chunk.getParentSessionId());
                })
                .anyMatch(entry -> anchorHitCounts.getOrDefault(entry.getKey(), 0) > 0);
        double confidence = topSessions.isEmpty() ? 0.0 : Math.min(1.0, topSessions.get(0).score);
        // 有向量距离时要求词法锚点或足够强的无锚点语义证据，防止“看起来相似”的负样本被当成答案。
        boolean answerable = !topSessions.isEmpty()
                && (lexicalEvidence || !vectorDistanceAvailable
                || (informativeKeywords.isEmpty() && confidence >= 0.35));
        List<String> reasonCodes = answerable
                ? List.of(lexicalEvidence ? "LEXICAL_EVIDENCE" : "STRONG_SEMANTIC_MATCH")
                : List.of(topSessions.isEmpty() ? "NO_CANDIDATES" : "NO_LEXICAL_EVIDENCE");

        log.debug("retrieval intent={} keywords={} phrases={} mode={} answerable={} confidence={} reasons={}",
                plan.intent(), keywords, quotedPhrases, hybrid ? "hybrid" : "keywordOnly",
                answerable, confidence, reasonCodes);
        return new SearchResponse(hybrid ? "hybrid" : "keywordOnly", plan.intent(),
                answerable, confidence, reasonCodes, answerable ? topSessions : List.of());
    }

    /**
     * Analysis Agent 的内部检索入口。
     *
     * <p>复用阶段 2 的 query plan、hybrid 降级和拒答规则；额外保留 chunk 消息边界、
     * 检索通道和会话分数，供阶段 3 evidence 审计使用。
     */
    public EvidenceRetrievalResponse retrieveEvidence(String chatFileId, EvidenceRetrievalRequest request) {
        SearchResponse response = search(chatFileId, new SearchRequest(request.query(), request.speaker(),
                request.startTime(), request.endTime(), request.sessionType(), request.resolvedTopK(), request.sortBy()));
        String channel = switch (response.mode()) {
            case "hybrid" -> "HYBRID_FUSED";
            case "structured" -> "STRUCTURED";
            default -> "KEYWORD";
        };
        List<EvidenceSession> sessions = response.sessions().stream().map(hit -> {
            List<EvidenceChunk> chunks = hit.hits.stream().map(chunkHit -> {
                RetrievalChunk chunk = chunkRepository.selectById(chunkHit.chunkId());
                return chunk == null ? null : new EvidenceChunk(chunk.getId(), chunk.getParentSessionId(),
                        chunk.getStartMessageId(), chunk.getEndMessageId(), chunk.getSequenceNo(), channel,
                        hit.score, chunk.getRetrievalText());
            }).filter(Objects::nonNull).toList();
            return new EvidenceSession(hit.sessionId, hit.startTime, hit.endTime, hit.sessionType,
                    hit.score, hit.formattedText, chunks);
        }).toList();
        return new EvidenceRetrievalResponse(response.mode(), response.intent(), response.answerable(),
                response.confidence(), response.reasonCodes(), sessions);
    }

    /**
     * CHUNK 重建后文档频率已变化，旧 IDF 不得继续参与排序。
     */
    public void invalidateIdfCache(String chatFileId) {
        String prefix = chatFileId + '\u0000';
        idfCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 结构化时间查询不经过向量排序。向量适合找内容相似度，不负责决定“第一次/最后一次”。
     */
    private SearchResponse structuredSearch(String chatFileId, SearchRequest req, QueryPlan plan) {
        var query = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ConversationSession>()
                .eq("chat_file_id", chatFileId);
        if (plan.startTime() != null) query.ge("end_time", plan.startTime());
        if (plan.endTime() != null) query.le("start_time", plan.endTime());
        if (plan.sessionType() != null) query.eq("session_type", plan.sessionType());
        if (plan.minMessageCount() > 0) query.ge("message_count", plan.minMessageCount());

        if (plan.conflictPeak()) {
            query.orderByDesc("message_count").orderByAsc("start_time");
        } else if ("timeDesc".equals(plan.sortBy())) {
            query.orderByDesc("start_time");
        } else {
            query.orderByAsc("start_time");
        }

        List<ConversationSession> sessions = sessionRepository.selectList(query);
        if (sessions == null) sessions = List.of();
        List<SessionHit> hits = sessions.stream()
                .limit(req.resolvedTopK())
                .map(s -> toSessionHit(s, 1.0d))
                .toList();
        boolean answerable = !hits.isEmpty();
        return new SearchResponse("structured", plan.intent(), answerable,
                answerable ? 1.0 : 0.0,
                List.of(answerable ? "STRUCTURED_MATCH" : "NO_CANDIDATES"), hits);
    }

    private SessionHit toSessionHit(ConversationSession session, double score) {
        var query = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RetrievalChunk>()
                .eq("parent_session_id", session.getId())
                .orderByAsc("sequence_no");
        List<RetrievalChunk> chunks = chunkRepository.selectList(query);
        if (chunks == null) chunks = List.of();
        List<ChunkHit> chunkHits = chunks.stream()
                .limit(2)
                .map(c -> new ChunkHit(c.getId(), c.getSequenceNo(), c.getRetrievalText()))
                .toList();
        return new SessionHit(session.getId(), session.getStartTime(), session.getEndTime(),
                session.getMessageCount() != null ? session.getMessageCount() : 0,
                session.getSessionType(), session.getSummary(), session.getFormattedText(),
                score, new ArrayList<>(chunkHits));
    }

    private QueryPlan buildQueryPlan(ChatFile chatFile, SearchRequest req) {
        String query = req.query();
        String lower = query.toLowerCase(Locale.ROOT);
        ZoneId zone;
        try {
            zone = ZoneId.of(chatFile.getSourceTimezone() != null
                    ? chatFile.getSourceTimezone() : "Asia/Shanghai");
        } catch (Exception e) {
            zone = ZoneId.of("Asia/Shanghai");
        }

        Instant start = req.startTime();
        Instant end = req.endTime();
        Matcher monthMatcher = MONTH_PATTERN.matcher(query);
        Matcher yearMatcher = YEAR_PATTERN.matcher(query);
        if (start == null && end == null && monthMatcher.find()) {
            YearMonth month = YearMonth.of(Integer.parseInt(monthMatcher.group(1)),
                    Integer.parseInt(monthMatcher.group(2)));
            start = month.atDay(1).atStartOfDay(zone).toInstant();
            end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusNanos(1);
        } else if (start == null && end == null && yearMatcher.find()) {
            int year = Integer.parseInt(yearMatcher.group(1));
            start = java.time.LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant();
            end = java.time.LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().minusNanos(1);
        }

        boolean first = containsAny(lower, "第一次", "首次", "最早");
        boolean last = containsAny(lower, "最后一次", "末次", "最近一次");
        boolean recontact = containsAny(lower, "重新联系", "重联");
        boolean conflictPeak = containsAny(lower, "吵架最多", "冲突最多", "争吵最多", "冲突高峰");
        // 显式 sort 只改变已召回候选的顺序；只有 query 本身表达时间锚点时才走全量结构化查询。
        boolean structured = first || last || recontact || conflictPeak;
        String sort = req.sortBy();
        if (sort == null || "score".equals(sort)) {
            sort = first || recontact ? "timeAsc" : last ? "timeDesc" : "score";
        }
        String sessionType = req.sessionType();
        if (conflictPeak && sessionType == null) sessionType = ConversationSession.TYPE_CONFLICT;
        int minMessages = last && containsAny(lower, "长聊", "聊得久", "长时间聊天")
                ? retrievalConfig().longChatMinMessages() : 0;
        QueryIntent intent;
        if (structured || containsAny(lower, "什么时候", "哪一年", "哪一天")) {
            intent = QueryIntent.TIMELINE;
        } else if (QUOTED_PHRASE_PATTERN.matcher(query).find()
                || containsAny(lower, "原话", "说过", "她说", "他说")) {
            intent = QueryIntent.EVIDENCE;
        } else if (containsAny(lower, "平时", "哪些", "喜欢什么", "怎么表达")) {
            intent = QueryIntent.AGGREGATE;
        } else {
            intent = QueryIntent.EVENT;
        }
        return new QueryPlan(intent, structured, start, end, sessionType, sort, minMessages, conflictPeak);
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private RelationshipAgentProperties.Retrieval retrievalConfig() {
        RelationshipAgentProperties.Retrieval configured = properties.retrieval();
        return configured != null ? configured : new RelationshipAgentProperties.Retrieval(5, 3);
    }

    private double keywordIdf(String chatFileId, String keyword) {
        return idfCache.computeIfAbsent(chatFileId + '\u0000' + keyword, ignored -> {
            long total = chunkRepository.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RetrievalChunk>()
                            .eq("chat_file_id", chatFileId));
            long frequency = chunkRepository.countContainingKeyword(chatFileId, keyword);
            return Math.log((total + 1.0) / (frequency + 1.0)) + 1.0;
        });
    }

    private List<String> extractQuotedPhrases(String query) {
        List<String> phrases = new ArrayList<>();
        Matcher matcher = QUOTED_PHRASE_PATTERN.matcher(query);
        while (matcher.find()) {
            String phrase = matcher.group(1).trim();
            if (!phrase.isEmpty()) phrases.add(phrase);
        }
        return phrases;
    }

    private List<String> extractEventSubjects(String query) {
        List<String> subjects = new ArrayList<>();
        Matcher matcher = EVENT_SUBJECT_PATTERN.matcher(query);
        while (matcher.find()) {
            String subject = matcher.group(1).trim();
            // 长句通常包含多个概念，交给分词处理；这里只补回 jieba 容易切错的短主题（如“初吻”）。
            if (subject.length() >= MIN_KW_LENGTH && subject.length() <= 6) subjects.add(subject);
        }
        return subjects;
    }

    private void addKeyword(List<String> keywords, String keyword) {
        if (keyword != null && !keyword.isBlank() && !keywords.contains(keyword)) keywords.add(keyword);
    }

    /**
     * 只扩展少量能由原文直接验证的事件表达，避免让向量近邻替代事实证据。
     */
    private void expandEventKeywords(String query, List<String> keywords) {
        if (query == null) return;
        if (query.contains("气到") || query.contains("气人") || query.contains("生气") || query.contains("发火")) {
            // “人气到”会被分词器误切为“人气”；此处语义实际是“某人被气到”。
            if (query.contains("人气到")) keywords.remove("人气");
            addKeyword(keywords, "生气");
            addKeyword(keywords, "气人");
            addKeyword(keywords, "发火");
            addKeyword(keywords, "大吵一架");
        }
        if (query.contains("没睡") || query.contains("不睡") || query.contains("睡不着")) {
            addKeyword(keywords, "没睡");
            addKeyword(keywords, "不睡");
            addKeyword(keywords, "睡不着");
        }
    }

    private List<String> compactKeywords(List<String> keywords) {
        return keywords.stream()
                .filter(term -> keywords.stream().noneMatch(other -> other.length() > term.length()
                        && other.contains(term)))
                .distinct()
                .toList();
    }

    /**
     * jieba SEARCH 模式 + 停用词过滤，并去掉被更长短语完全包含的冗余词。
     */
    private List<String> extractKeywords(String query) {
        List<String> result = new ArrayList<>();
        for (var token : JIEBA.process(query, com.huaban.analysis.jieba.JiebaSegmenter.SegMode.SEARCH)) {
            String trimmed = token.word.trim();
            if (trimmed.length() >= MIN_KW_LENGTH && !STOP_WORDS.contains(trimmed)) {
                if (!result.contains(trimmed)) result.add(trimmed);
            }
        }
        // Jieba can split uncommon two-character catchphrases into single characters.
        // Preserve a short exact phrase as a lexical anchor without turning whole
        // natural-language questions into broad ILIKE patterns.
        if (query != null && query.length() <= 4) {
            Matcher matcher = SHORT_HAN_PHRASE_PATTERN.matcher(query);
            while (matcher.find()) {
                String phrase = matcher.group();
                if (!STOP_WORDS.contains(phrase) && !GENERIC_QUERY_TERMS.contains(phrase)) addKeyword(result, phrase);
            }
        }
        return compactKeywords(result);
    }

    public enum QueryIntent {TIMELINE, EVENT, EVIDENCE, AGGREGATE}

    private record QueryPlan(QueryIntent intent, boolean structured, Instant startTime, Instant endTime,
                             String sessionType, String sortBy, int minMessageCount,
                             boolean conflictPeak) {
    }

    // ---- 响应 DTO（SessionHit.score 需可变——聚合时取 max，故不用 record） ----
    public record SearchResponse(String mode, QueryIntent intent, boolean answerable,
                                 double confidence, List<String> reasonCodes,
                                 List<SessionHit> sessions) {
    }

    public record ChunkHit(String chunkId, int sequenceNo, String retrievalText) {
    }

    /**
     * Internal request only; it is deliberately not a controller DTO.
     */
    public record EvidenceRetrievalRequest(String query, String speaker, Instant startTime, Instant endTime,
                                           String sessionType, Integer topK, String sortBy) {
        public int resolvedTopK() {
            return topK != null ? Math.min(topK, 10) : 3;
        }
    }

    /**
     * Auditable retrieval result used to construct model-visible evidence packets.
     */
    public record EvidenceRetrievalResponse(String mode, QueryIntent intent, boolean answerable,
                                            double confidence, List<String> reasonCodes,
                                            List<EvidenceSession> sessions) {
    }

    public record EvidenceSession(String sessionId, Instant startTime, Instant endTime, String sessionType,
                                  double score, String formattedText, List<EvidenceChunk> chunks) {
    }

    public record EvidenceChunk(String chunkId, String sessionId, String startMessageId, String endMessageId,
                                Integer sequenceNo, String retrievalChannel, double score, String retrievalText) {
    }

    public static class SessionHit {
        public String sessionId;
        public Instant startTime;
        public Instant endTime;
        public int messageCount;
        public String sessionType;
        public String summary;
        public String formattedText;
        public double score;
        public List<ChunkHit> hits;

        public SessionHit(String sessionId, Instant startTime, Instant endTime,
                          int messageCount, String sessionType, String summary,
                          String formattedText, double score, List<ChunkHit> hits) {
            this.sessionId = sessionId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.messageCount = messageCount;
            this.sessionType = sessionType;
            this.summary = summary;
            this.formattedText = formattedText;
            this.score = score;
            this.hits = hits;
        }
    }
}
