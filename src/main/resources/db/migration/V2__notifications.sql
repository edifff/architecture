-- Очередь оповещений, ожидающих доставки. Строка существует до того момента,
-- пока пользователь, бывший offline в момент отправки сообщения, не подключится
-- и не получит оповещение. Благодаря этому накопленные оповещения переживают
-- аварийное завершение процесса.
CREATE TABLE notifications (
    recipient VARCHAR NOT NULL REFERENCES users (name),
    message_id BIGINT NOT NULL REFERENCES messages (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (recipient, message_id)
);
