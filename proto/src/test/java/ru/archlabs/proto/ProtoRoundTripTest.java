package ru.archlabs.proto;

import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import org.junit.jupiter.api.Test;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.common.Empty;
import ru.archlabs.proto.common.UserState;
import ru.archlabs.proto.storage.ChatStorageGrpc;
import ru.archlabs.proto.storage.ClearNotificationsRequest;
import ru.archlabs.proto.storage.UserStatesResponse;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контрактный тест модуля proto: сериализация/десериализация каждого типа
 * запроса и ответа обоих сервисов, литеральные номера полей (перенумерация
 * ломает тест, хотя round-trip её и не заметил бы) и наличие сгенерированных
 * дескрипторов сервисов. Docker не нужен.
 */
final class ProtoRoundTripTest {

    /** Время с микросекундной точностью — как в колонках TIMESTAMP WITH TIME ZONE. */
    private static final Instant SENT_AT = Instant.parse("2026-10-21T09:15:30.123456Z");

    // --- номера полей --------------------------------------------------------

    @Test
    void messageFieldNumbersAreStable() {
        assertEquals(1, ru.archlabs.proto.common.Message.ID_FIELD_NUMBER);
        assertEquals(2, ru.archlabs.proto.common.Message.SENDER_FIELD_NUMBER);
        assertEquals(3, ru.archlabs.proto.common.Message.TEXT_FIELD_NUMBER);
        assertEquals(4, ru.archlabs.proto.common.Message.SENT_AT_FIELD_NUMBER);
    }

    @Test
    void userStateFieldNumbersAreStable() {
        assertEquals(1, UserState.NAME_FIELD_NUMBER);
        assertEquals(2, UserState.ONLINE_FIELD_NUMBER);
        assertEquals(3, UserState.LAST_CONNECTED_FIELD_NUMBER);
        assertEquals(4, UserState.NOTIFICATIONS_ENABLED_FIELD_NUMBER);
    }

    @Test
    void repeatedFieldNumbersAreStable() {
        // Repeated-поля: перенумерация здесь особенно опасна — «тихая» потеря
        // списка на границе версий.
        assertEquals(1, ru.archlabs.proto.chat.MessagesResponse.MESSAGES_FIELD_NUMBER);
        assertEquals(1, ru.archlabs.proto.storage.MessagesResponse.MESSAGES_FIELD_NUMBER);
        assertEquals(1, UserStatesResponse.USERS_FIELD_NUMBER);
        assertEquals(2, ClearNotificationsRequest.MESSAGE_IDS_FIELD_NUMBER);
    }

    // --- round-trip ----------------------------------------------------------

    @Test
    void timestampSurvivesRoundTripAtMicrosecondPrecision() throws Exception {
        var message =
                ru.archlabs.proto.common.Message.newBuilder()
                        .setId(42)
                        .setSender("ivan")
                        .setText("привет")
                        .setSentAt(fromInstant(SENT_AT))
                        .build();

        var parsed = roundTrip(message);

        assertEquals(message, parsed);
        // Наносекунды сохраняются точно: 123456 микросекунд -> 123456000 наносекунд.
        assertEquals(123_456_000, parsed.getSentAt().getNanos());
        assertEquals(SENT_AT, toInstant(parsed.getSentAt()));
    }

    @Test
    void commonMessagesRoundTrip() throws Exception {
        roundTrip(Empty.getDefaultInstance());

        var userState =
                UserState.newBuilder()
                        .setName("ivan")
                        .setOnline(true)
                        .setLastConnected(fromInstant(SENT_AT))
                        .setNotificationsEnabled(true)
                        .build();
        var parsedUserState = roundTrip(userState);
        assertEquals(userState, parsedUserState);
        assertEquals(SENT_AT, toInstant(parsedUserState.getLastConnected()));
    }

    @Test
    void storageRequestsAndResponsesRoundTrip() throws Exception {
        roundTrip(
                ru.archlabs.proto.storage.ConnectRequest.newBuilder()
                        .setName("ivan")
                        .setAt(fromInstant(SENT_AT))
                        .build());
        roundTrip(
                ru.archlabs.proto.storage.DisconnectRequest.newBuilder()
                        .setName("ivan")
                        .setAt(fromInstant(SENT_AT))
                        .build());
        roundTrip(
                ru.archlabs.proto.storage.SetNotificationsEnabledRequest.newBuilder()
                        .setName("ivan")
                        .setEnabled(false)
                        .build());
        roundTrip(
                ru.archlabs.proto.storage.AppendRequest.newBuilder()
                        .setSender("ivan")
                        .setText("привет")
                        .setSentAt(fromInstant(SENT_AT))
                        .build());
        roundTrip(
                ru.archlabs.proto.storage.UndeliveredNotificationsRequest.newBuilder()
                        .setRecipient("olga")
                        .build());
        // Repeated int64 внутри запроса на очистку уведомлений.
        roundTrip(
                ClearNotificationsRequest.newBuilder()
                        .setRecipient("olga")
                        .addAllMessageIds(java.util.List.of(1L, 2L, 999_999L))
                        .build());
        roundTrip(ru.archlabs.proto.storage.HistoryRequest.getDefaultInstance());
        roundTrip(ru.archlabs.proto.storage.UserRequest.newBuilder().setName("ivan").build());

        // Repeated-ответы: история и список онлайн-пользователей.
        var history =
                ru.archlabs.proto.storage.MessagesResponse.newBuilder()
                        .addMessages(message(1, "ivan", "раз"))
                        .addMessages(message(2, "olga", "два"))
                        .build();
        var parsedHistory = roundTrip(history);
        assertEquals(history, parsedHistory);
        assertEquals(2, parsedHistory.getMessagesCount());
        assertEquals("два", parsedHistory.getMessages(1).getText());

        var online =
                UserStatesResponse.newBuilder()
                        .addUsers(userState("ivan", true))
                        .addUsers(userState("olga", false))
                        .build();
        var parsedOnline = roundTrip(online);
        assertEquals(online, parsedOnline);
        assertEquals(2, parsedOnline.getUsersCount());
        assertFalse(parsedOnline.getUsers(1).getOnline());
    }

    @Test
    void chatRequestsAndResponsesRoundTrip() throws Exception {
        roundTrip(ru.archlabs.proto.chat.ConnectRequest.newBuilder().setName("ivan").build());
        roundTrip(
                ru.archlabs.proto.chat.ConnectResponse.newBuilder()
                        .setUser(userState("ivan", true))
                        .build());
        roundTrip(ru.archlabs.proto.chat.DisconnectRequest.newBuilder().setName("ivan").build());
        roundTrip(
                ru.archlabs.proto.chat.SendRequest.newBuilder()
                        .setName("ivan")
                        .setText("привет")
                        .build());
        roundTrip(ru.archlabs.proto.chat.HistoryRequest.newBuilder().setName("ivan").build());
        roundTrip(
                ru.archlabs.proto.chat.SetNotificationsEnabledRequest.newBuilder()
                        .setName("ivan")
                        .setEnabled(false)
                        .build());
        roundTrip(ru.archlabs.proto.chat.UserRequest.newBuilder().setName("ivan").build());
        roundTrip(ru.archlabs.proto.chat.SubscribeRequest.newBuilder().setName("ivan").build());

        var history =
                ru.archlabs.proto.chat.MessagesResponse.newBuilder()
                        .addMessages(message(1, "ivan", "привет"))
                        .build();
        assertEquals(history, roundTrip(history));
    }

    // --- дескрипторы сервисов ------------------------------------------------

    @Test
    void serviceDescriptorsExist() {
        var storage = ChatStorageGrpc.getServiceDescriptor();
        assertEquals("storage.ChatStorage", storage.getName());
        assertEquals(10, storage.getMethods().size());

        var chat = ChatServiceGrpc.getServiceDescriptor();
        assertEquals("chat.ChatService", chat.getName());
        assertEquals(7, chat.getMethods().size());

        assertFalse(storage.getMethods().isEmpty());
        assertTrue(
                chat.getMethods().stream()
                        .anyMatch(
                                method ->
                                        method.getType()
                                                == io.grpc.MethodDescriptor.MethodType
                                                        .SERVER_STREAMING),
                "Subscribe должна быть серверным потоком");
    }

    // --- helpers -------------------------------------------------------------

    /** Round-trip через байты: то, что уходит в wire, должно вернуться равным. */
    @SuppressWarnings("unchecked")
    private static <T extends Message> T roundTrip(T message)
            throws com.google.protobuf.InvalidProtocolBufferException {
        T parsed = (T) message.getParserForType().parseFrom(message.toByteArray());
        assertEquals(message, parsed, "round-trip must preserve the message");
        return parsed;
    }

    private static Timestamp fromInstant(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static ru.archlabs.proto.common.Message message(long id, String sender, String text) {
        return ru.archlabs.proto.common.Message.newBuilder()
                .setId(id)
                .setSender(sender)
                .setText(text)
                .setSentAt(fromInstant(SENT_AT))
                .build();
    }

    private static UserState userState(String name, boolean online) {
        return UserState.newBuilder()
                .setName(name)
                .setOnline(online)
                .setLastConnected(fromInstant(SENT_AT))
                .setNotificationsEnabled(true)
                .build();
    }
}
