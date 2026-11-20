package cn.ksmcbrigade.jhookmod.utils;

import cn.ksmcbrigade.jhookmod.Constants;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Name translation helpers for Fabric.
 *
 * <p>In development the game runs with <b>named</b> names (e.g. {@code net.minecraft.client.Minecraft});
 * in production it runs with <b>intermediary</b> names (e.g. {@code net.minecraft.class_310}).
 * Strings in bytecode are never remapped, so code that builds a name as a string has to translate
 * it itself.</p>
 *
 * <h2>Portability</h2>
 * <p>{@link MappingResolver} only exposes {@code mapMethodName}/{@code mapFieldName}
 * (intermediary → runtime); the reverse for members is not part of the public API. To still offer
 * {@link #getObfMethodName(Method)} the loader internals are used <b>reflectively</b> and cached
 * once: no compile-time dependency on tiny-remapper, and any failure simply degrades to
 * {@link Method#getName()} instead of breaking the mod.</p>
 */
public final class FabricMappingsUtils {

    private static final String INTERMEDIARY = "intermediary";

    /** True once the reverse member map has been built (successfully or not). */
    private static volatile boolean memberMapBuilt;
    /** named owner#name#desc -> intermediary name. */
    private static volatile Map<String, String> methodToIntermediary = Collections.emptyMap();
    /** named owner#name#desc -> intermediary name. */
    private static volatile Map<String, String> fieldToIntermediary = Collections.emptyMap();

    private FabricMappingsUtils() {
    }

    // ------------------------------------------------------------------
    //  Classes: runtime <-> intermediary (public API, both directions)
    // ------------------------------------------------------------------

    /** Runtime class name → intermediary name; identity in production. */
    public static String getObfClassName(String runtimeClassName) {
        if (isEmpty(runtimeClassName)) {
            return runtimeClassName;
        }
        try {
            String mapped = resolver().unmapClassName(INTERMEDIARY, runtimeClassName);
            return mapped == null ? runtimeClassName : mapped;
        } catch (Throwable t) {
            Constants.LOG.debug("[Mappings] cannot unmap class {}", runtimeClassName, t);
            return runtimeClassName;
        }
    }

    /** Intermediary class name → runtime class name; identity in production. */
    public static String getRuntimeClassName(String intermediaryName) {
        if (isEmpty(intermediaryName)) {
            return intermediaryName;
        }
        try {
            String mapped = resolver().mapClassName(INTERMEDIARY, intermediaryName);
            return mapped == null ? intermediaryName : mapped;
        } catch (Throwable t) {
            Constants.LOG.debug("[Mappings] cannot map class {}", intermediaryName, t);
            return intermediaryName;
        }
    }

    /** Convenience kept from the original skeleton: treats {@code name} as a class name. */
    public static String getObfName(String name) {
        return getObfClassName(name);
    }

    // ------------------------------------------------------------------
    //  Members: portable helpers keyed by java.lang.reflect.Method
    // ------------------------------------------------------------------

    /**
     * Intermediary (production) name of the given method.
     *
     * <p>Returns {@code method.getName()} when the name cannot be resolved — in production the
     * runtime name already is the intermediary name, and in development that is the best safe
     * fallback. Never throws.</p>
     */
    public static String getObfMethodName(Method method) {
        if (method == null) {
            return null;
        }
        buildMemberMaps();
        String key = memberKey(method.getDeclaringClass().getName(), method.getName(), descriptor(method));
        String mapped = methodToIntermediary.get(key);
        return mapped == null ? method.getName() : mapped;
    }

    /**
     * Intermediary (production) name of a method described by strings.
     *
     * @param runtimeOwner runtime class name that declares the method
     * @param name         runtime method name
     * @param desc         JVM descriptor, e.g. {@code (I)V}
     */
    public static String getObfMethodName(String runtimeOwner, String name, String desc) {
        if (isEmpty(runtimeOwner) || isEmpty(name) || isEmpty(desc)) {
            return name;
        }
        buildMemberMaps();
        String mapped = methodToIntermediary.get(memberKey(runtimeOwner, name, desc));
        return mapped == null ? name : mapped;
    }

    /**
     * Intermediary (production) name of the given field.
     * <p>Returns {@code field.getName()} when it cannot be resolved. Never throws.</p>
     */
    public static String getObfFieldName(java.lang.reflect.Field field) {
        if (field == null) {
            return null;
        }
        buildMemberMaps();
        String key = memberKey(field.getDeclaringClass().getName(), field.getName(), descriptor(field.getType()));
        String mapped = fieldToIntermediary.get(key);
        return mapped == null ? field.getName() : mapped;
    }

    /** Intermediary method name → runtime method name (public API direction). */
    public static String getRuntimeMethodName(String intermediaryOwner, String name, String desc) {
        if (isEmpty(intermediaryOwner) || isEmpty(name) || isEmpty(desc)) {
            return name;
        }
        try {
            String mapped = resolver().mapMethodName(INTERMEDIARY, intermediaryOwner, name, desc);
            return mapped == null ? name : mapped;
        } catch (Throwable t) {
            Constants.LOG.debug("[Mappings] cannot map method {}#{}{}", intermediaryOwner, name, desc, t);
            return name;
        }
    }

    /** The namespace the game is currently running with ("named" in dev, "intermediary" in prod). */
    public static String getRuntimeNamespace() {
        try {
            return resolver().getCurrentRuntimeNamespace();
        } catch (Throwable t) {
            return FabricLoader.getInstance().isDevelopmentEnvironment() ? "named" : INTERMEDIARY;
        }
    }

    /** Whether the game currently runs with intermediary (production) names. */
    public static boolean isIntermediaryRuntime() {
        return INTERMEDIARY.equals(getRuntimeNamespace());
    }

    // ------------------------------------------------------------------
    //  Reverse member map, built once through the loader internals
    // ------------------------------------------------------------------

    private static void buildMemberMaps() {
        if (memberMapBuilt) {
            return;
        }
        synchronized (FabricMappingsUtils.class) {
            if (memberMapBuilt) {
                return;
            }
            memberMapBuilt = true;
            if (isIntermediaryRuntime()) {
                return; // runtime names already are intermediary names
            }
            try {
                Object launcher = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase")
                        .getMethod("getLauncher").invoke(null);
                Object configuration = launcher.getClass().getMethod("getMappingConfiguration").invoke(launcher);
                Object mappings = configuration.getClass().getMethod("getMappings").invoke(configuration);
                String targetNamespace = (String) launcher.getClass().getMethod("getTargetNamespace").invoke(launcher);
                if (targetNamespace == null || INTERMEDIARY.equals(targetNamespace)) {
                    return;
                }

                Class<?> helper = Class.forName("net.fabricmc.loader.impl.util.mappings.TinyRemapperMappingsHelper");
                Object provider = helper
                        .getMethod("create", mappings.getClass(), String.class, String.class)
                        .invoke(null, mappings, targetNamespace, INTERMEDIARY);

                Class<?> acceptorInterface = Class.forName(
                        "net.fabricmc.loader.impl.lib.tinyremapper.IMappingProvider$MappingAcceptor");
                Map<String, String> methods = new HashMap<>();
                Map<String, String> fields = new HashMap<>();

                Object acceptor = Proxy.newProxyInstance(
                        acceptorInterface.getClassLoader(), new Class<?>[]{acceptorInterface},
                        (proxy, invoked, args) -> {
                            if (args != null && args.length == 2 && args[0] != null) {
                                String key = memberKeyOf(args[0]);
                                String destination = (String) args[1];
                                if ("acceptMethod".equals(invoked.getName()) && key != null) {
                                    methods.put(key, destination);
                                } else if ("acceptField".equals(invoked.getName()) && key != null) {
                                    fields.put(key, destination);
                                }
                            }
                            return null;
                        });

                provider.getClass().getMethod("load", acceptorInterface).invoke(provider, acceptor);
                methodToIntermediary = methods;
                fieldToIntermediary = fields;
                Constants.LOG.debug("[Mappings] reverse member map built ({} methods, {} fields)",
                        methods.size(), fields.size());
            } catch (Throwable t) {
                Constants.LOG.debug("[Mappings] reverse member map unavailable; falling back to runtime names", t);
            }
        }
    }

    /** Reads {@code owner/name/desc} from a tiny-remapper {@code Member} without linking to it. */
    private static String memberKeyOf(Object member) {
        try {
            Class<?> type = member.getClass();
            String owner = (String) readMemberField(type, member, "owner");
            String name = (String) readMemberField(type, member, "name");
            String desc = (String) readMemberField(type, member, "desc");
            return owner == null || name == null || desc == null ? null : memberKey(owner, name, desc);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object readMemberField(Class<?> type, Object member, String fieldName) throws Exception {
        try {
            java.lang.reflect.Field field = type.getField(fieldName);
            return field.get(member);
        } catch (NoSuchFieldException e) {
            Method getter = type.getMethod(fieldName);
            return getter.invoke(member);
        }
    }

    // ------------------------------------------------------------------
    //  Small helpers
    // ------------------------------------------------------------------

    private static String memberKey(String owner, String name, String desc) {
        return owner + "#" + name + "#" + desc;
    }

    private static String descriptor(Method method) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) {
            sb.append(descriptor(parameter));
        }
        return sb.append(')').append(descriptor(method.getReturnType())).toString();
    }

    private static String descriptor(Class<?> type) {
        if (type == void.class) {
            return "V";
        }
        if (type == boolean.class) {
            return "Z";
        }
        if (type == byte.class) {
            return "B";
        }
        if (type == char.class) {
            return "C";
        }
        if (type == short.class) {
            return "S";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == float.class) {
            return "F";
        }
        if (type == double.class) {
            return "D";
        }
        if (type.isArray()) {
            return "[" + descriptor(type.getComponentType());
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static MappingResolver resolver() {
        return FabricLoader.getInstance().getMappingResolver();
    }
}
