package com.example.relationshipagent.statistics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 统计服务(M6):会话构建完成后计算基础统计并 UPSERT 进 statistics_cache。
 * <p>
 * 范围控制:仅实现设计文档 7.6 基础项;文体指纹/末端分析/事件候选属阶段 2-3;
 * topKeywords 需分词库,本期留空数组(阶段 2 引入 HanLP/jieba 再补)。
 * <p>
 * 计算全部在 Java 侧一次 pass 完成(单文件 27K 行内存可承受),Jackson 序列化为 JSON String 入库。
 *
 * <p>阶段 2 M6 扩展：topKeywords (jieba)、yearlyMessageTrend、styleFingerprint。
 */
@Service
public class StatisticsService {

    private static final Logger log = LoggerFactory.getLogger(StatisticsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter YEAR_FMT = DateTimeFormatter.ofPattern("yyyy");

    /**
     * jieba 分词器（线程安全，进程内复用）
     */
    private static final com.huaban.analysis.jieba.JiebaSegmenter JIEBA = new com.huaban.analysis.jieba.JiebaSegmenter();

    /**
     * 停用词表（去常见中文停用词、单字词、纯标点）
     */
    private static final Set<String> STOP_WORDS = loadStopWords();

    private final MessageRepository messageRepository;
    private final ConversationSessionRepository sessionRepository;
    private final StatisticsCacheRepository statisticsCacheRepository;
    private final ChatFileRepository chatFileRepository;
    private final com.example.relationshipagent.config.RelationshipAgentProperties properties;

    public StatisticsService(MessageRepository messageRepository,
                             ConversationSessionRepository sessionRepository,
                             StatisticsCacheRepository statisticsCacheRepository,
                             ChatFileRepository chatFileRepository,
                             com.example.relationshipagent.config.RelationshipAgentProperties properties) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.statisticsCacheRepository = statisticsCacheRepository;
        this.chatFileRepository = chatFileRepository;
        this.properties = properties;
    }

    /**
     * 计算并缓存统计(设计文档 4.4:会话构建完成后仅自动刷新统计缓存——唯一允许的自动级联)。
     */
    public void computeAndCache(String chatFileId) {
        ChatFile chatFile = chatFileRepository.selectById(chatFileId);
        ZoneId zoneId = ZoneId.of(chatFile != null && chatFile.getSourceTimezone() != null
                ? chatFile.getSourceTimezone() : "Asia/Shanghai");

        List<Message> messages = messageRepository.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getChatFileId, chatFileId)
                .orderByAsc(Message::getMessageTime).orderByAsc(Message::getSourceLocalId));
        List<ConversationSession> sessions = sessionRepository.selectList(new LambdaQueryWrapper<ConversationSession>()
                .eq(ConversationSession::getChatFileId, chatFileId)
                .orderByAsc(ConversationSession::getStartTime));

        Map<String, Object> stats = compute(messages, sessions, zoneId);
        String json = toJson(stats);
        upsert(chatFileId, json);
        log.info("Statistics computed & cached: chatFileId={}, sessions={}, messages={}",
                chatFileId, sessions.size(), messages.size());
    }

    /**
     * 读取缓存的 stats_json;无缓存返回 null(由 controller 决定 404)。
     */
    public String getCached(String chatFileId) {
        StatisticsCache cache = statisticsCacheRepository.selectOne(new LambdaQueryWrapper<StatisticsCache>()
                .eq(StatisticsCache::getChatFileId, chatFileId));
        return cache != null ? cache.getStatsJson() : null;
    }

    private Map<String, Object> compute(List<Message> messages, List<ConversationSession> sessions, ZoneId zoneId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        // totalMessages + timeRange
        stats.put("totalMessages", messages.size());
        if (messages.isEmpty()) {
            stats.put("timeRange", Map.of());
        } else {
            Map<String, String> range = new LinkedHashMap<>();
            range.put("start", messages.get(0).getMessageTime().toString());
            range.put("end", messages.get(messages.size() - 1).getMessageTime().toString());
            stats.put("timeRange", range);
        }

        // speakerMessageCount(与 SELECT speaker, count(id) GROUP BY speaker 一致)
        Map<String, Integer> speakerCount = new LinkedHashMap<>();
        for (Message m : messages) speakerCount.merge(m.getSpeaker(), 1, Integer::sum);
        stats.put("speakerMessageCount", speakerCount);

        // monthlyMessageTrend(按源时区截取月份 + speaker 透视)
        stats.put("monthlyMessageTrend", monthlyTrend(messages, speakerCount.keySet(), zoneId));

        // averageReplyDelay(排除跨会话与系统消息;对方最后一条→本方首次回应)
        stats.put("averageReplyDelay", averageReplyDelay(messages, sessions, speakerCount.keySet()));

        // sessionCount + averageSessionDuration
        stats.put("sessionCount", sessions.size());
        stats.put("averageSessionDuration", averageSessionDuration(sessions));

        // mostActiveHours(EXTRACT HOUR 计数取 top3)
        stats.put("mostActiveHours", mostActiveHours(messages, zoneId));

        // M6: topKeywords（jieba 分词 Top 50）
        stats.put("topKeywords", topKeywords(messages));

        // M6: yearlyMessageTrend（按月趋势数据按年聚合）
        stats.put("yearlyMessageTrend", yearlyTrend(messages, speakerCount.keySet(), zoneId));

        // M6: styleFingerprint（per-person 文体指纹）
        stats.put("styleFingerprint", styleFingerprint(messages, speakerCount.keySet(), zoneId));

        return stats;
    }

    private List<Map<String, Object>> monthlyTrend(List<Message> messages, java.util.Set<String> speakers, ZoneId zoneId) {
        Map<String, Map<String, Integer>> byMonth = new TreeMap<>();
        for (Message m : messages) {
            String month = MONTH_FMT.format(m.getMessageTime().atZone(zoneId));
            byMonth.computeIfAbsent(month, k -> {
                Map<String, Integer> row = new LinkedHashMap<>();
                for (String sp : speakers) row.put(sp, 0);
                return row;
            }).merge(m.getSpeaker(), 1, Integer::sum);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> e : byMonth.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", e.getKey());
            row.putAll(e.getValue());
            trend.add(row);
        }
        return trend;
    }

    private Map<String, Object> averageReplyDelay(List<Message> messages, List<ConversationSession> sessions,
                                                  java.util.Set<String> speakers) {
        // sessions 顺序划分 messages(构建时即按此顺序切分),逐会话重置 pending,排除跨会话
        Map<String, List<Long>> delays = new LinkedHashMap<>();
        for (String sp : speakers) delays.put(sp, new ArrayList<>());

        Map<String, Instant> pendingOther = new LinkedHashMap<>(); // 各 speaker 的"对方最后一条"时间
        int msgIdx = 0;
        for (ConversationSession s : sessions) {
            pendingOther.clear();
            int count = s.getMessageCount() != null ? s.getMessageCount() : 0;
            for (int k = 0; k < count && msgIdx < messages.size(); k++, msgIdx++) {
                Message m = messages.get(msgIdx);
                String mt = m.getMessageType();
                if (Message.TYPE_SYSTEM.equals(mt) || "SYS_NOTICE".equals(mt)) continue; // 排除系统消息
                String spk = m.getSpeaker();
                if (spk == null) continue;
                Instant t = m.getMessageTime();
                Instant pending = pendingOther.remove(spk);
                if (pending != null) {
                    long delay = Duration.between(pending, t).getSeconds();
                    if (delay >= 0) delays.computeIfAbsent(spk, x -> new ArrayList<>()).add(delay);
                }
                // 对所有非本方 speaker:本条即"对方最后一条"
                for (String other : speakers) {
                    if (!other.equals(spk)) pendingOther.put(other, t);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String sp : speakers) {
            List<Long> d = delays.getOrDefault(sp, new ArrayList<>());
            List<Long> sorted = new ArrayList<>(d);
            sorted.sort(Comparator.naturalOrder());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("p50", percentile(sorted, 0.5));
            row.put("p90", percentile(sorted, 0.9));
            row.put("samples", sorted.size());
            result.put(sp, row);
        }
        return result;
    }

    private double averageSessionDuration(List<ConversationSession> sessions) {
        if (sessions.isEmpty()) return 0.0;
        long sum = 0;
        int n = 0;
        for (ConversationSession s : sessions) {
            if (s.getDurationSeconds() != null) {
                sum += s.getDurationSeconds();
                n++;
            }
        }
        return n == 0 ? 0.0 : (double) sum / n;
    }

    private List<Map<String, Object>> mostActiveHours(List<Message> messages, ZoneId zoneId) {
        Map<Integer, Integer> hourCount = new TreeMap<>();
        for (Message m : messages) {
            int hour = m.getMessageTime().atZone(zoneId).getHour();
            hourCount.merge(hour, 1, Integer::sum);
        }
        return hourCount.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("hour", e.getKey());
                    row.put("count", e.getValue());
                    return row;
                }).toList();
    }

    // ===== M6 新增统计项 =====

    /**
     * Top 50 关键词（jieba 分词 + 去停用词，仅统计 TEXT/EMOJI 消息 cleanedContent）
     */
    private List<Map<String, Object>> topKeywords(List<Message> messages) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (Message m : messages) {
            String mt = m.getMessageType();
            if (!"TEXT".equals(mt) && !"EMOJI".equals(mt)) continue;
            String text = m.getCleanedContent();
            if (text == null || text.isBlank()) continue;
            for (com.huaban.analysis.jieba.SegToken token : JIEBA.process(text, com.huaban.analysis.jieba.JiebaSegmenter.SegMode.SEARCH)) {
                String word = token.word.trim();
                if (word.length() < 2 || STOP_WORDS.contains(word)) continue;
                if (word.matches("^[\\p{Punct}\\p{IsPunctuation}]+$")) continue;
                freq.merge(word, 1, Integer::sum);
            }
        }
        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(50)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("word", e.getKey());
                    row.put("count", e.getValue());
                    return row;
                })
                .toList();
    }

    /**
     * 按年聚合消息趋势（复用月度趋势 pass）
     */
    private List<Map<String, Object>> yearlyTrend(List<Message> messages, Set<String> speakers, ZoneId zoneId) {
        Map<String, Map<String, Integer>> byYear = new TreeMap<>();
        for (Message m : messages) {
            String year = YEAR_FMT.format(m.getMessageTime().atZone(zoneId));
            byYear.computeIfAbsent(year, k -> {
                Map<String, Integer> row = new LinkedHashMap<>();
                for (String sp : speakers) row.put(sp, 0);
                return row;
            }).merge(m.getSpeaker(), 1, Integer::sum);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> e : byYear.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", e.getKey());
            row.putAll(e.getValue());
            trend.add(row);
        }
        return trend;
    }

    /**
     * Per-person 文体指纹（设计文档 7.6）
     */
    private Map<String, Map<String, Object>> styleFingerprint(List<Message> messages,
                                                              Set<String> speakers, ZoneId zoneId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        List<String> catchphrases = properties.statistics().catchphrases();
        int mergeSeconds = properties.session().displayMergeSeconds();

        for (String spk : speakers) {
            List<Message> personMsgs = messages.stream()
                    .filter(m -> spk.equals(m.getSpeaker())).toList();
            List<Message> textMsgs = personMsgs.stream()
                    .filter(m -> "TEXT".equals(m.getMessageType()) || "EMOJI".equals(m.getMessageType()))
                    .toList();

            Map<String, Object> fp = new LinkedHashMap<>();
            fp.put("messageCount", personMsgs.size());

            // 消息长度分位数（仅 TEXT/EMOJI）
            List<Integer> lengths = textMsgs.stream()
                    .map(m -> m.getCleanedContent() != null ? m.getCleanedContent().length() : 0)
                    .sorted().toList();
            fp.put("lengthP50", percentileInt(lengths, 0.5));
            fp.put("lengthP90", percentileInt(lengths, 0.9));

            // ≤5 字消息占比
            long shortCount = lengths.stream().filter(l -> l <= 5).count();
            fp.put("shortMessageRatio", lengths.isEmpty() ? 0.0 : (double) shortCount / lengths.size());

            // 连发 burst
            fp.put("burstAvgSize", burstAvgSize(messages, spk, mergeSeconds));

            // 句末标点分布
            fp.put("punctuation", punctuationDist(textMsgs));

            // 表情包占比
            long emojiCount = personMsgs.stream().filter(m -> "EMOJI".equals(m.getMessageType())).count();
            fp.put("emojiMessageRatio", personMsgs.isEmpty() ? 0.0 : (double) emojiCount / personMsgs.size());

            // 口头禅每千条命中次数
            Map<String, Double> catchHits = new LinkedHashMap<>();
            double per1k = personMsgs.isEmpty() ? 0.0 : 1000.0 / personMsgs.size();
            for (String cp : catchphrases) {
                long hits = personMsgs.stream()
                        .filter(m -> m.getCleanedContent() != null && m.getCleanedContent().contains(cp))
                        .count();
                catchHits.put(cp, Math.round(hits * per1k * 10.0) / 10.0);
            }
            fp.put("catchphraseHitsPer1k", catchHits);

            result.put(spk, fp);
        }
        return result;
    }

    /**
     * 同说话人 60 秒内连续消息的平均连发条数
     */
    private double burstAvgSize(List<Message> messages, String speaker, int mergeSeconds) {
        List<Integer> bursts = new ArrayList<>();
        int current = 0;
        Instant prevTime = null;
        for (Message m : messages) {
            if (!speaker.equals(m.getSpeaker())) {
                if (current > 0) bursts.add(current + 1);
                current = 0;
                prevTime = null;
                continue;
            }
            if (prevTime != null) {
                long gap = java.time.Duration.between(prevTime, m.getMessageTime()).getSeconds();
                if (gap <= mergeSeconds) {
                    current++;
                } else {
                    if (current > 0) bursts.add(current + 1);
                    current = 0;
                }
            }
            prevTime = m.getMessageTime();
        }
        if (current > 0) bursts.add(current + 1);
        return bursts.isEmpty() ? 0.0 : bursts.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    /**
     * 句末标点分布（最后一个字符）
     */
    private Map<String, Double> punctuationDist(List<Message> textMsgs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (Message m : textMsgs) {
            String c = m.getCleanedContent();
            if (c == null || c.isEmpty()) continue;
            char last = c.charAt(c.length() - 1);
            String key = switch (last) {
                case '。' -> "。";
                case '？' -> "？";
                case '！' -> "！";
                case '~', '～' -> "~";
                case '…' -> "…";
                case '，', ',' -> "none";
                default -> "none";
            };
            if (!"。？！~…".contains(String.valueOf(last))) key = "none";
            counts.merge(key, 1, Integer::sum);
            total++;
        }
        Map<String, Double> dist = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet())
            dist.put(e.getKey(), total == 0 ? 0.0 : (double) e.getValue() / total);
        return dist;
    }

    /**
     * 整数百分位（与 SQL percentile_cont 语义一致）
     */
    private int percentileInt(List<Integer> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        if (sorted.size() == 1) return sorted.get(0);
        double rank = p * (sorted.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) return sorted.get(lower);
        double frac = rank - lower;
        return (int) Math.round(sorted.get(lower) + frac * (sorted.get(upper) - sorted.get(lower)));
    }

    /**
     * 内置停用词表（常见中文停用词 + 单字词，约 200 条）
     */
    private static Set<String> loadStopWords() {
        Set<String> words = new java.util.HashSet<>(Set.of(
                "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
                "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
                "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "什么",
                "怎么", "为什么", "因为", "所以", "但是", "如果", "然后", "可以", "还是",
                "这个", "那个", "吗", "吧", "啊", "呢", "哦", "嗯", "哈", "呀", "嘛",
                "还", "能", "想", "让", "被", "把", "给", "对", "从", "没", "多", "少",
                "大", "小", "来", "出", "做", "过", "得", "地", "之", "已", "与", "或",
                "等", "几", "只", "又", "可", "但", "才", "再", "真", "太", "比", "较",
                "更", "最", "所", "其", "中", "以", "为", "而", "且", "虽", "然"
        ));
        // 尝试从类路径加载外部停用词文件
        try {
            var is = StatisticsService.class.getClassLoader().getResourceAsStream("stopwords.txt");
            if (is != null) {
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String w = line.trim();
                        if (!w.isEmpty() && !w.startsWith("#")) words.add(w);
                    }
                }
            }
        } catch (Exception ignored) { /* 文件不存在则用内置 */ }
        return words;
    }

    /**
     * 线性插值百分位,与 PostgreSQL percentile_cont 语义一致。
     */
    private double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0.0;
        int n = sorted.size();
        if (n == 1) return sorted.get(0);
        double rank = p * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) return sorted.get(lower);
        double frac = rank - lower;
        return sorted.get(lower) + frac * (sorted.get(upper) - sorted.get(lower));
    }

    private String toJson(Map<String, Object> stats) {
        try {
            return MAPPER.writeValueAsString(stats);
        } catch (Exception e) {
            log.warn("Failed to serialize statistics, falling back to empty object", e);
            return "{}";
        }
    }

    private void upsert(String chatFileId, String json) {
        StatisticsCache existing = statisticsCacheRepository.selectOne(new LambdaQueryWrapper<StatisticsCache>()
                .eq(StatisticsCache::getChatFileId, chatFileId));
        Instant now = Instant.now();
        if (existing == null) {
            StatisticsCache cache = new StatisticsCache();
            cache.setId(UUID.randomUUID().toString());
            cache.setChatFileId(chatFileId);
            cache.setStatsJson(json);
            cache.setComputedAt(now);
            statisticsCacheRepository.insert(cache);
        } else {
            existing.setStatsJson(json);
            existing.setComputedAt(now);
            statisticsCacheRepository.updateById(existing);
        }
    }
}
