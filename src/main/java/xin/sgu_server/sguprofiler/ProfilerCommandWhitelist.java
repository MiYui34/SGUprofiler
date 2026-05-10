package xin.sgu_server.sguprofiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 游戏内指令维护的采样命令使用者白名单，持久化至 {@code config/sguprofiler_command_whitelist.json}。 */
public final class ProfilerCommandWhitelist {
    private static final Object SAVE_LOCK = new Object();
    private static final Collection<UUID> IDS = ConcurrentHashMap.newKeySet();
    private static Path file;
    private static Logger log;

    private ProfilerCommandWhitelist() {
    }

    public static void init(Path configDir, Logger logger) {
        log = logger;
        file = configDir.resolve("sguprofiler_command_whitelist.json");
        synchronized (SAVE_LOCK) {
            IDS.clear();
            if (!Files.isRegularFile(file)) {
                return;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(text);
                if (!root.isJsonArray()) {
                    return;
                }
                for (JsonElement e : root.getAsJsonArray()) {
                    if (e != null && e.isJsonPrimitive()) {
                        try {
                            IDS.add(UUID.fromString(e.getAsString()));
                        } catch (IllegalArgumentException ex) {
                            log.warn("[SGUProfiler] skip invalid uuid in whitelist file: {}", e);
                        }
                    }
                }
            } catch (IOException | JsonParseException ex) {
                log.warn("[SGUProfiler] could not read {}: {}", file.toAbsolutePath(), ex.toString());
            }
        }
    }

    public static boolean contains(UUID id) {
        return id != null && IDS.contains(id);
    }

    public static int size() {
        return IDS.size();
    }

    public static boolean add(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        synchronized (SAVE_LOCK) {
            if (!IDS.add(uuid)) {
                return false;
            }
            saveLocked();
            return true;
        }
    }

    public static boolean remove(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        synchronized (SAVE_LOCK) {
            if (!IDS.remove(uuid)) {
                return false;
            }
            saveLocked();
            return true;
        }
    }

    public static int clear() {
        synchronized (SAVE_LOCK) {
            int n = IDS.size();
            IDS.clear();
            saveLocked();
            return n;
        }
    }

    /** 稳定排序后的快照，用于展示。 */
    public static List<UUID> snapshotSorted() {
        List<UUID> list = new ArrayList<>(IDS);
        Collections.sort(list);
        return list;
    }

    private static void saveLocked() {
        JsonArray arr = new JsonArray();
        List<UUID> sorted = new ArrayList<>(IDS);
        Collections.sort(sorted);
        for (UUID id : sorted) {
            arr.add(id.toString());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, arr.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            if (log != null) {
                log.warn("[SGUProfiler] could not write {}: {}", file.toAbsolutePath(), ex.toString());
            }
        }
    }
}
