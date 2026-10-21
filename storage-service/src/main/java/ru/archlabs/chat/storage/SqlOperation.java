package ru.archlabs.chat.storage;

import java.sql.SQLException;

@FunctionalInterface
interface SqlOperation<T> {
    T get() throws SQLException;
}
