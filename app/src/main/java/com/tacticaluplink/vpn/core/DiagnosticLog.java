package com.tacticaluplink.vpn.core;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class DiagnosticLog {

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    private static final int MAX_LINES = 800;
    private static final String GLOBAL_TAG = "TacticalUplink";
    private static final ArrayDeque<Entry> BUFFER = new ArrayDeque<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static volatile File spillFile;

    private DiagnosticLog() {
    }

    public static void d(String tag, String message) {
        append(Level.DEBUG, tag, message, null);
    }

    public static void i(String tag, String message) {
        append(Level.INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        append(Level.WARN, tag, message, null);
    }

    public static void e(String tag, String message, Throwable error) {
        append(Level.ERROR, tag, message, error);
    }

    public static void e(String tag, String message) {
        append(Level.ERROR, tag, message, null);
    }

    private static synchronized void append(Level level, String tag, String message, Throwable error) {
        String line = message != null ? message : "";
        if (error != null && error.getMessage() != null) {
            line = line + " | " + error.getMessage();
        }
        Entry entry = new Entry(System.currentTimeMillis(), level, tag, line);
        BUFFER.addLast(entry);
        while (BUFFER.size() > MAX_LINES) {
            BUFFER.removeFirst();
        }
        appendSpill('[' + TS.format(new Date(entry.timeMs)) + "] " + level.name() + '/' + tag + ": " + line);
        switch (level) {
            case DEBUG:
                Log.d(GLOBAL_TAG + "/" + tag, line);
                break;
            case INFO:
                Log.i(GLOBAL_TAG + "/" + tag, line);
                break;
            case WARN:
                Log.w(GLOBAL_TAG + "/" + tag, line);
                break;
            case ERROR:
                if (error != null) {
                    Log.e(GLOBAL_TAG + "/" + tag, line, error);
                } else {
                    Log.e(GLOBAL_TAG + "/" + tag, line);
                }
                break;
            default:
                break;
        }
    }

    public static synchronized void clear() {
        BUFFER.clear();
    }

    public static void bindSpillFile(File file) {
        spillFile = file;
    }

    private static void appendSpill(String line) {
        File f = spillFile;
        if (f == null) {
            return;
        }
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line);
            w.write('\n');
        } catch (Exception ignored) {
            // best-effort for adb diagnostics
        }
    }

    public static synchronized String exportText(int maxLines) {
        int limit = maxLines <= 0 ? MAX_LINES : Math.min(maxLines, MAX_LINES);
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, BUFFER.size() - limit);
        int i = 0;
        for (Entry e : BUFFER) {
            if (i++ < skip) {
                continue;
            }
            sb.append('[').append(TS.format(new Date(e.timeMs))).append("] ")
                    .append(e.level.name()).append('/').append(e.tag).append(": ")
                    .append(e.message).append('\n');
        }
        return sb.toString();
    }

    public static synchronized JSONArray exportJson(int maxLines) {
        int limit = maxLines <= 0 ? MAX_LINES : Math.min(maxLines, MAX_LINES);
        JSONArray arr = new JSONArray();
        int skip = Math.max(0, BUFFER.size() - limit);
        int i = 0;
        for (Entry e : BUFFER) {
            if (i++ < skip) {
                continue;
            }
            try {
                JSONObject o = new JSONObject();
                o.put("t", e.timeMs);
                o.put("level", e.level.name());
                o.put("tag", e.tag);
                o.put("msg", e.message);
                arr.put(o);
            } catch (Exception ignored) {
                // skip malformed
            }
        }
        return arr;
    }

    private static final class Entry {
        final long timeMs;
        final Level level;
        final String tag;
        final String message;

        Entry(long timeMs, Level level, String tag, String message) {
            this.timeMs = timeMs;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }
    }
}
