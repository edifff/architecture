package ru.archlabs.chat.http;

import com.google.protobuf.Timestamp;
import ru.archlabs.proto.common.UserState;

/** Состояние пользователя в JSON-протоколе клиента. */
public record UserPayload(
        String name, boolean online, String lastConnected, boolean notificationsEnabled) {
    public static UserPayload from(UserState state) {
        Timestamp lastConnected = state.getLastConnected();
        return new UserPayload(
                state.getName(),
                state.getOnline(),
                lastConnected.getSeconds() == 0 && lastConnected.getNanos() == 0
                        ? null
                        : java.time.Instant.ofEpochSecond(
                                        lastConnected.getSeconds(), lastConnected.getNanos())
                                .toString(),
                state.getNotificationsEnabled());
    }
}
