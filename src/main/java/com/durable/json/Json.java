package com.durable.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.UncheckedIOException;

/**
 * JSON 读写的最小封装。
 *
 * 存在的意义：让日志的 payload 序列化只有一处实现，
 * 将来换掉 JSON 库时不需要动业务代码。
 */
public final class Json {

    private static final ObjectMapper MAPPER = createMapper();

    private Json() {
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /** 对象 → JSON 字符串。 */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("序列化失败: " + value.getClass().getName(), e);
        }
    }

    /** JSON 字符串 → 对象。 */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("反序列化失败: " + json, e);
        }
    }

    /** JSON 字符串 → 泛型对象（如 List、Map）。 */
    public static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("反序列化失败: " + json, e);
        } catch (UncheckedIOException e) {
            throw new IllegalArgumentException("读取失败: " + json, e);
        }
    }

    /**
     * 规范化为稳定的字符串形式：对象键按字典序排列，去掉无意义空白。
     *
     * 存在的意义：给「审批绑定的参数快照」算指纹时，
     * {@code {"a":1,"b":2}} 和 {@code { "b": 2, "a": 1 }} 必须得到同一个指纹，
     * 否则审批会因为我们无法控制的键序变化而误判为「参数被篡改」。
     */
    public static String canonical(String json) {
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            return MAPPER.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(parsed);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("无法规范化 JSON: " + json, e);
        }
    }
}
