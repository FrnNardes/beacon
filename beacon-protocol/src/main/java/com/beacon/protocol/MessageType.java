package com.beacon.protocol;

/**
 * Complete vocabulary of the Beacon protocol.
 * Determines message direction, expected fields, and transport (TCP/UDP).
 */
public enum MessageType {

    // Authentication (TCP)
    LOGIN, LOGIN_OK, LOGIN_ERROR,

    // Chat (TCP)
    MESSAGE, PRIVATE,

    // User management (TCP)
    LIST, USER_LIST, JOINED, LEFT, QUIT,

    // Search & statistics (TCP)
    SEARCH, SEARCH_RESULT, STATS, STATS_RESULT,

    // History (TCP) — sent on login, rendered dimmed on client
    HISTORY,

    // File transfer (TCP) — optional feature
    FILE_META, FILE_DATA,

    // Heartbeat + RTT measurement (TCP)
    PING, PONG,

    // UDP-only
    DISCOVER_SERVER, SERVER_HERE, TYPING,

    // Generic error (TCP)
    ERROR
}
