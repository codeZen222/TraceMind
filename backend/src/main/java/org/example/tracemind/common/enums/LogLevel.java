package org.example.tracemind.common.enums;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志级别。取值以前端 {@code src/types/log.ts} 的
 * {@code type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'} 为准——共 5 级，别漏 TRACE。
 *
 * <p>ES 里 {@code level} 是 {@code keyword} 类型，查询走的是精确匹配，
 * 所以大小写必须统一——这里全大写，采集时也要统一转成大写再入 ES。</p>
 */
public enum LogLevel {

    TRACE,

    DEBUG,

    INFO,

    WARN,

    ERROR;

    public String code() {
        return name();
    }

    /** 是否算"错误"，决定要不要触发诊断。WARN 不触发，避免诊断风暴 */
    public boolean isError() {
        return this == ERROR;
    }

    public static LogLevel of(String code) {
        if (code == null) {
            return null;
        }
        for (LogLevel level : values()) {
            if (level.name().equalsIgnoreCase(code)) {
                return level;
            }
        }
        return null;
    }

    /**
     * 行内"级别单词"的匹配器。
     *
     * <p>关键是 {@code \b} 词边界：正文/字段名里的 {@code traceId}、{@code stackTrace}、
     * 类名 {@code ErrorHandler} 都不是独立单词，不会被当成级别。
     * 这是老实现的病根——它直接对整行做 {@code contains("TRACE")}，
     * 于是一行「收到请求 traceId=t000…」被判成 TRACE，日志级别整个错位。</p>
     */
    private static final Pattern LEVEL_TOKEN =
            Pattern.compile("\\b(TRACE|DEBUG|WARN(?:ING)?|INFO|ERROR|SEVERE|FATAL)\\b");

    /**
     * 只扫行首这一段。
     * <p>真实日志的级别字段一定出现在开头（{@code 时间 级别 [线程] logger - 正文}），
     * 越过后面的正文不参与判断——否则正文里提到 "ERROR"/"INFO" 的字眼会盖掉真实级别。</p>
     */
    private static final int LEVEL_SCAN_LIMIT = 120;

    /**
     * 从不规范的日志行文本里猜级别。
     * <p>采集链路专用：真实日志有 {@code [ERROR]}、{@code ERROR c.f.Bar -}、{@code 2026-.. ERROR ..}
     * 好几种写法，这里统一收口，别在 collector 里散落一堆 {@code contains}。
     * 猜不出返回 null，由调用方决定默认值（现有两处调用方都兜底成 INFO）。</p>
     *
     * <p>取行首扫描区内<b>位置最靠前</b>的那个独立级别单词——一行日志的级别就是排在最前面的那个，
     * 这样 {@code INFO ... 转换 ERROR 码失败} 会正确判成 INFO，而不是按"最高级别优先"判成 ERROR。</p>
     */
    public static LogLevel parseFromLine(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return null;
        }
        String head = rawLine.length() > LEVEL_SCAN_LIMIT
                ? rawLine.substring(0, LEVEL_SCAN_LIMIT)
                : rawLine;
        Matcher matcher = LEVEL_TOKEN.matcher(head.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            LogLevel level = ofToken(matcher.group(1));
            if (level != null) {
                return level;
            }
        }
        return null;
    }

    /** 各家框架的级别写法不一样：SEVERE/FATAL 归 ERROR，WARNING 归 WARN。 */
    private static LogLevel ofToken(String token) {
        switch (token) {
            case "ERROR":
            case "SEVERE":
            case "FATAL":
                return ERROR;
            case "WARN":
            case "WARNING":
                return WARN;
            case "INFO":
                return INFO;
            case "DEBUG":
                return DEBUG;
            case "TRACE":
                return TRACE;
            default:
                return null;
        }
    }
}
