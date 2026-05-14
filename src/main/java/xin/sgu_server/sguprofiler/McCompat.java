package xin.sgu_server.sguprofiler;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 避免在源码中写 {@code //?}（与 Stonecutter active 版本子工程冲突），也避免对 Yarn 名使用
 * {@code Class.forName}。在运行时用「签名」解析 intermediary 下的方法，各 MC 子工程编译同一份即可。
 */
public final class McCompat {
    private static volatile Method cachedEntityGetWorld;
    private static volatile Method cachedIsOperatorMethod;
    private static volatile boolean cachedIsOperatorUsesGameProfileDirectly;
    /** {@code true} 时直接向 {@link #cachedIsOperatorMethod} 传入 {@link ServerPlayerEntity}。 */
    private static volatile boolean cachedIsOperatorUsesServerPlayer;
    private static volatile Method cachedHasPermissionLevelMethod;

    private McCompat() {
    }

    public static MinecraftServer serverOf(ServerPlayerEntity player) {
        return worldOf(player).getServer();
    }

    public static World worldOf(Entity entity) {
        try {
            Method m = cachedEntityGetWorld;
            if (m == null) {
                synchronized (McCompat.class) {
                    m = cachedEntityGetWorld;
                    if (m == null) {
                        m = resolveEntityWorldMethod();
                        m.setAccessible(true);
                        cachedEntityGetWorld = m;
                    }
                }
            }
            return (World) m.invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Method resolveEntityWorldMethod() throws NoSuchMethodException {
        try {
            return Entity.class.getMethod("getWorld");
        } catch (NoSuchMethodException e) {
            return Entity.class.getMethod("getEntityWorld");
        }
    }

    public static boolean isOperator(PlayerManager manager, ServerPlayerEntity player) {
        try {
            Method m = cachedIsOperatorMethod;
            boolean directProfile = cachedIsOperatorUsesGameProfileDirectly;
            boolean directPlayer = cachedIsOperatorUsesServerPlayer;
            if (m == null) {
                synchronized (McCompat.class) {
                    m = cachedIsOperatorMethod;
                    if (m == null) {
                        resolveIsOperatorMethod();
                        m = cachedIsOperatorMethod;
                        directProfile = cachedIsOperatorUsesGameProfileDirectly;
                        directPlayer = cachedIsOperatorUsesServerPlayer;
                    }
                }
            }
            if (directPlayer) {
                return (Boolean) m.invoke(manager, player);
            }
            GameProfile profile = player.getGameProfile();
            Object arg = directProfile ? profile : buildIsOperatorArg(m.getParameterTypes()[0], profile, player);
            return (Boolean) m.invoke(manager, arg);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void resolveIsOperatorMethod() throws NoSuchMethodException {
        try {
            Method byProfile = PlayerManager.class.getMethod("isOperator", GameProfile.class);
            byProfile.setAccessible(true);
            cachedIsOperatorMethod = byProfile;
            cachedIsOperatorUsesGameProfileDirectly = true;
            cachedIsOperatorUsesServerPlayer = false;
            return;
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method byPlayer = PlayerManager.class.getMethod("isOperator", ServerPlayerEntity.class);
            byPlayer.setAccessible(true);
            cachedIsOperatorMethod = byPlayer;
            cachedIsOperatorUsesGameProfileDirectly = false;
            cachedIsOperatorUsesServerPlayer = true;
            return;
        } catch (NoSuchMethodException ignored) {
        }

        List<Method> candidates = new ArrayList<>();
        for (Method method : collectDeclaredMethods(PlayerManager.class)) {
            if (!isBooleanUnary(method)) {
                continue;
            }
            Class<?> p = method.getParameterTypes()[0];
            if (p == GameProfile.class) {
                method.setAccessible(true);
                cachedIsOperatorMethod = method;
                cachedIsOperatorUsesGameProfileDirectly = true;
                cachedIsOperatorUsesServerPlayer = false;
                return;
            }
            if (p == ServerPlayerEntity.class) {
                method.setAccessible(true);
                cachedIsOperatorMethod = method;
                cachedIsOperatorUsesGameProfileDirectly = false;
                cachedIsOperatorUsesServerPlayer = true;
                return;
            }
            if (p == Object.class) {
                continue;
            }
            candidates.add(method);
        }
        for (Method method : candidates) {
            Class<?> p = method.getParameterTypes()[0];
            if (canBuildIsOperatorArg(p)) {
                method.setAccessible(true);
                cachedIsOperatorMethod = method;
                cachedIsOperatorUsesGameProfileDirectly = false;
                cachedIsOperatorUsesServerPlayer = false;
                return;
            }
        }
        throw new NoSuchMethodException(
                "PlayerManager: no boolean(GameProfile|ServerPlayerEntity|constructible) isOperator-like method");
    }

    private static List<Method> collectDeclaredMethods(Class<?> type) {
        List<Method> out = new ArrayList<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        stack.add(type);
        while (!stack.isEmpty()) {
            Class<?> c = stack.removeFirst();
            if (c == null || c == Object.class) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                out.add(m);
            }
            stack.add(c.getSuperclass());
        }
        return out;
    }

    private static boolean isBooleanUnary(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt != boolean.class && rt != Boolean.class) {
            return false;
        }
        return m.getParameterCount() == 1;
    }

    private static boolean canBuildIsOperatorArg(Class<?> p) {
        return findGameProfileConstructor(p) != null || !findProfileGetters(ServerPlayerEntity.class, p).isEmpty();
    }

    private static Object buildIsOperatorArg(Class<?> param, GameProfile profile, ServerPlayerEntity player)
            throws ReflectiveOperationException {
        Constructor<?> c = findGameProfileConstructor(param);
        if (c != null) {
            c.setAccessible(true);
            return c.newInstance(profile);
        }
        for (Method getter : findProfileGetters(ServerPlayerEntity.class, param)) {
            getter.setAccessible(true);
            Object v = getter.invoke(player);
            if (v != null && param.isAssignableFrom(v.getClass())) {
                return v;
            }
        }
        throw new NoSuchMethodException("Cannot build isOperator arg for " + param.getName());
    }

    private static Constructor<?> findGameProfileConstructor(Class<?> p) {
        try {
            return p.getDeclaredConstructor(GameProfile.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static List<Method> findProfileGetters(Class<?> start, Class<?> wanted) {
        List<Method> out = new ArrayList<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        stack.add(start);
        while (!stack.isEmpty()) {
            Class<?> c = stack.removeFirst();
            if (c == null || c == Object.class) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                    continue;
                }
                if (wanted.isAssignableFrom(m.getReturnType())) {
                    out.add(m);
                }
            }
            stack.add(c.getSuperclass());
        }
        return out;
    }

    public static boolean hasCommandPermissionLevel(ServerCommandSource source, int level) {
        try {
            Method m = cachedHasPermissionLevelMethod;
            if (m == null) {
                synchronized (McCompat.class) {
                    m = cachedHasPermissionLevelMethod;
                    if (m == null) {
                        m = resolveHasPermissionLevelMethod();
                        m.setAccessible(true);
                        cachedHasPermissionLevelMethod = m;
                    }
                }
            }
            return (Boolean) m.invoke(source, level);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Method resolveHasPermissionLevelMethod() throws NoSuchMethodException {
        List<Method> matches = new ArrayList<>();
        for (Method method : ServerCommandSource.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?> rt = method.getReturnType();
            if (rt != boolean.class && rt != Boolean.class) {
                continue;
            }
            if (method.getParameterCount() != 1) {
                continue;
            }
            Class<?> p = method.getParameterTypes()[0];
            if (p != int.class && p != Integer.class) {
                continue;
            }
            matches.add(method);
        }
        if (matches.isEmpty()) {
            throw new NoSuchMethodException("ServerCommandSource: no boolean(int) permission method");
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        Class<?> root = ServerCommandSource.class;
        matches.sort(Comparator
                .comparingInt((Method m) -> breadthFirstTypeDistance(root, m.getDeclaringClass()))
                .thenComparing(m -> m.getDeclaringClass().getName())
                .thenComparing(Method::getName));
        return matches.get(0);
    }

    private static int breadthFirstTypeDistance(Class<?> root, Class<?> declaring) {
        if (root == declaring) {
            return 0;
        }
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(root);
        seen.add(root);
        int dist = 1;
        while (!queue.isEmpty()) {
            int level = queue.size();
            for (int i = 0; i < level; i++) {
                Class<?> c = queue.removeFirst();
                Class<?> sup = c.getSuperclass();
                if (sup != null && sup != Object.class && seen.add(sup)) {
                    if (sup == declaring) {
                        return dist;
                    }
                    queue.add(sup);
                }
                for (Class<?> iface : c.getInterfaces()) {
                    if (seen.add(iface)) {
                        if (iface == declaring) {
                            return dist;
                        }
                        queue.add(iface);
                    }
                }
            }
            dist++;
        }
        return Integer.MAX_VALUE;
    }

    public static Optional<GameProfile> findProfileByUuid(MinecraftServer server, UUID uuid) {
        try {
            Method getUserCache = MinecraftServer.class.getMethod("getUserCache");
            Object userCache = getUserCache.invoke(server);
            Method getByUuid = userCache.getClass().getMethod("getByUuid", UUID.class);
            @SuppressWarnings("unchecked")
            Optional<GameProfile> r = (Optional<GameProfile>) getByUuid.invoke(userCache, uuid);
            return r;
        } catch (NoSuchMethodException e) {
            return findProfileByUuidViaServices(server, uuid);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Optional<GameProfile> findProfileByUuidViaServices(MinecraftServer server, UUID uuid) {
        try {
            Method getApiServices = MinecraftServer.class.getMethod("getApiServices");
            Object apiServices = getApiServices.invoke(server);
            Method profileResolver = apiServices.getClass().getMethod("profileResolver");
            Object resolver = profileResolver.invoke(apiServices);
            Method getProfileById = resolver.getClass().getMethod("getProfileById", UUID.class);
            @SuppressWarnings("unchecked")
            Optional<GameProfile> r = (Optional<GameProfile>) getProfileById.invoke(resolver, uuid);
            return r;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static String gameProfileName(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        try {
            Method m = profile.getClass().getMethod("getName");
            return (String) m.invoke(profile);
        } catch (NoSuchMethodException e) {
            try {
                Method m = profile.getClass().getMethod("name");
                return (String) m.invoke(profile);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
