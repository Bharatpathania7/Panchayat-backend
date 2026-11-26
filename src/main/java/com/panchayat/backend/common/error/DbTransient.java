package com.panchayat.backend.common.error;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * Is this failure the database being briefly unreachable (busy pool, restart, shutdown, refused or reset connection)?
 * Such a request did not commit anything, so the app can safely try again in a moment: it gets 503 + Retry-After instead
 * of an error screen. Real problems (a broken query, a duplicate key, a bug) are not transient and stay 500.
 */
public final class DbTransient {

    private DbTransient() {
    }

    public static boolean is(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < 20; t = t.getCause(), depth++) {
            if (t instanceof SQLTransientConnectionException) return true; // pool had no free connection in time
            if (t instanceof java.net.ConnectException || t instanceof java.net.SocketException || t instanceof java.io.EOFException) return true;
            if (t instanceof SQLException s) {
                String state = s.getSQLState();
                // 08xxx = connection exception; 57P01 admin shutdown, 57P02 crash shutdown, 57P03 cannot connect now (starting up)
                if (state != null && (state.startsWith("08") || state.equals("57P01") || state.equals("57P02") || state.equals("57P03"))) return true;
            }
            String m = t.getMessage();
            if (m != null && (m.contains("the database system is shutting down") || m.contains("the database system is starting up")
                    || m.contains("terminating connection due to administrator command") || m.contains("Connection refused")
                    || m.contains("This connection has been closed") || m.contains("Connection is closed")
                    || m.contains("Unable to rollback against JDBC Connection") || m.contains("Connection reset")
                    || m.contains("An I/O error occurred while sending to the backend"))) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
