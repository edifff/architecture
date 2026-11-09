package ru.archlabs.chat.http;

import java.util.regex.Pattern;

record Route(String method, Pattern pattern, RouteHandler handler) {
}
