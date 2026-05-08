package xin.sgu_server.sguprofiler;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 自网站白名单接口拉取并缓存的正版玩家 UUID。 */
public final class ProfilerAllowlistCache {
    private static final Set<UUID> IDS = ConcurrentHashMap.newKeySet();

    private ProfilerAllowlistCache() {
    }

    public static void replaceAll(Collection<UUID> ids) {
        IDS.clear();
        for (UUID id : ids) {
            if (id != null) {
                IDS.add(id);
            }
        }
    }

    public static boolean contains(UUID id) {
        return id != null && IDS.contains(id);
    }

    public static int size() {
        return IDS.size();
    }

    public static Set<UUID> snapshot() {
        return Collections.unmodifiableSet(Set.copyOf(IDS));
    }
}
