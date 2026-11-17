package ru.archlabs.chat;

import ru.archlabs.chat.domain.Message;

/**
 * A stored status message together with its local fan-out counters.
 */
public record StatusDispatch(Message message, DeliverySummary summary) {
}
