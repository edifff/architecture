package ru.archlabs.chat.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Преобразование объектов протокола в JSON и обратно. */
public final class ChatJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatJson() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize JSON", exception);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot parse JSON", exception);
        }
    }
}
