package cn.ksmcbrigade.jhookmod.utils.agent;

import com.sun.tools.attach.VirtualMachine;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.util.*;

/**
 * &#064;Author: KSmc_brigade
 * &#064;Date: 2025/11/8 上午9:01
 */
public class UnsafeUtils {
    public static final Unsafe UNSAFE = getUnsafe();
    public static final MethodHandles.Lookup lookup = (MethodHandles.Lookup)getFieldValue(MethodHandles.Lookup.class, "IMPL_LOOKUP", MethodHandles.Lookup.class);
    private static final Object internalUNSAFE = getInternalUNSAFE();
    private static MethodHandle objectFieldOffsetInternal;

    private static Class<?> enableNativeAccessClazz = null;

    private static Unsafe getUnsafe() {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (Unsafe)theUnsafe.get((Object)null);
        } catch (Exception var1) {
            Exception e = var1;
            e.printStackTrace();
            return null;
        }
    }

    private static Object getInternalUNSAFE() {
        try {
            Class<?> clazz = lookup.findClass("jdk.internal.misc.Unsafe");
            return lookup.findStatic(clazz, "getUnsafe", MethodType.methodType(clazz)).invoke();
        } catch (Throwable var1) {
            Throwable e = var1;
            e.printStackTrace();
            return null;
        }
    }

    public static <T> T getFieldValue(Field f, Object target, Class<T> clazz) {
        try {
            long offset;
            if (Modifier.isStatic(f.getModifiers())) {
                target = UNSAFE.staticFieldBase(f);
                offset = UNSAFE.staticFieldOffset(f);
            } else {
                offset = objectFieldOffset(f);
            }

            return (T) UNSAFE.getObject(target, offset);
        } catch (Throwable var5) {
            var5.printStackTrace();
            return null;
        }
    }

    public static long objectFieldOffset(Field f) {
        try {
            return UNSAFE.objectFieldOffset(f);
        } catch (Throwable var4) {
            try {
                return (long) objectFieldOffsetInternal.invoke(f);
            } catch (Throwable var3) {
                var3.printStackTrace();
                return 0L;
            }
        }
    }

    public static <T> T getFieldValue(Object target, String fieldName, Class<T> clazz) {
        try {
            return getFieldValue(target.getClass().getDeclaredField(fieldName), target, clazz);
        } catch (Throwable var4) {
            var4.printStackTrace();
            return null;
        }
    }

    public static <T> T getFieldValueWithClass(Class<?> cz,Object target, String fieldName, Class<T> clazz) {
        try {
            return getFieldValue(cz.getDeclaredField(fieldName), target, clazz);
        } catch (Throwable var4) {
            var4.printStackTrace();
            return null;
        }
    }

    public static <T> T getFieldValue(Class<?> target, String fieldName, Class<T> clazz) {
        try {
            return getFieldValue((Field)target.getDeclaredField(fieldName), (Object)null, clazz);
        } catch (Throwable var4) {
            var4.printStackTrace();
            return null;
        }
    }

    public static void setFieldValue(Object target, Class<?> value) {
        try {
            int aVolatile = 0;
            if (UNSAFE != null) {
                aVolatile = UNSAFE.getIntVolatile(UNSAFE.allocateInstance(value), 8L);
            }
            if (UNSAFE != null) {
                UNSAFE.putIntVolatile(target, 8L, aVolatile);
            }
        } catch (Throwable var3) {
            var3.printStackTrace();
        }

    }

    public static void setFieldValue(Object target, String fieldName, Object value) {
        try {
            setFieldValueF(target.getClass().getDeclaredField(fieldName), target, value);
        } catch (Throwable var4) {
            var4.printStackTrace();
        }

    }

    public static void setFieldValueC(Class<?> ct,Object target, String fieldName, Object value) {
        try {
            setFieldValueF(ct.getDeclaredField(fieldName), target, value);
        } catch (Throwable var4) {
            var4.printStackTrace();
        }

    }

    public static void setFieldValueF(Field f, Object target, Object value) {
        try {
            long offset = 0;
            if (Modifier.isStatic(f.getModifiers())) {
                if (UNSAFE != null) {
                    target = UNSAFE.staticFieldBase(f);
                }
                if (UNSAFE != null) {
                    offset = UNSAFE.staticFieldOffset(f);
                }
            } else {
                offset = objectFieldOffset(f);
            }

            if (UNSAFE != null) {
                UNSAFE.putObject(target, offset, value);
            }
        } catch (Throwable var5) {
            var5.printStackTrace();
        }

    }

    public static String getJarPath(Class<?> clazz) {
        String file = clazz.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (!file.isEmpty()) {
            if (file.startsWith("union:")) {
                file = file.substring(6);
            }

            if (file.startsWith("/")) {
                file = file.substring(1);
            }

            file = file.substring(0, file.lastIndexOf(".jar") + 4);
            file = file.replaceAll("/", "\\\\");
        }

        String result = URLDecoder.decode(file, StandardCharsets.UTF_8).replace("\\", FileSystems.getDefault().getSeparator());
        StringBuilder builder = getStringBuilder(result);
        return builder.toString();
    }

    private static @NotNull StringBuilder getStringBuilder(String result) {
        String prefix = (FileSystems.getDefault().getSeparator().equals("/")?"/":"");
        File n = new File(result +prefix);
        StringBuilder builder = new StringBuilder(n.getParent()+FileSystems.getDefault().getSeparator());
        boolean a = false;
        for (String string : n.getName().split("")) {
            if(string.equals("A")){
                a = true;
            }
            if(!a){
                builder.append(string.replace("_","!"));
            }
            else{
                builder.append(string);
            }
        }
        return builder;
    }

    private static void allowAttachSelf() {
        System.setProperty("jdk.attach.allowAttachSelf", "true");

        try {
            Class vmClass = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
            Field allowAttachSelfField = vmClass.getDeclaredField("ALLOW_ATTACH_SELF");
            Object base = UNSAFE.staticFieldBase(allowAttachSelfField);
            long offset = UNSAFE.staticFieldOffset(allowAttachSelfField);
            UNSAFE.putBoolean(base, offset, true);
        } catch (NoSuchFieldException | ClassNotFoundException var5) {
        }

    }

    public static void enableSelfAttach() {
        try {
            try {
                System.setProperty("jdk.attach.allowAttachSelf", "true");
                Field field = Class.forName("sun.tools.attach.HotSpotVirtualMachine").getDeclaredField("ALLOW_ATTACH_SELF");
                UNSAFE.putBoolean(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field), true);
            } catch (Exception var1) {
                allowAttachSelf();
            }

        } catch (Exception var2) {
            throw new RuntimeException(var2);
        }
    }

    private static void openAttachModule() {
        Module currentModule = UnsafeUtils.class.getModule();
        ModuleLayer bootLayer = ModuleLayer.boot();
        Optional attachModuleOpt = bootLayer.findModule("jdk.attach");
        if (attachModuleOpt.isEmpty()) {
            throw new RuntimeException("jdk.attach module not found");
        } else {
            Module attachModule = (Module)attachModuleOpt.get();

            try {
                MethodHandle implAddOpens = lookup.findVirtual(Module.class, "implAddOpens", MethodType.methodType(Void.TYPE, String.class, Module.class));
                implAddOpens.invoke(attachModule, "sun.tools.attach", currentModule);
            } catch (Throwable var6) {
                try {
                    Map openPackages = (Map)getFieldValue((Object)attachModule, (String)"openPackages", Map.class);
                    if (openPackages == null) {
                        openPackages = new HashMap();
                        setFieldValue((Object)attachModule, (String)"openPackages", openPackages);
                    }

                    ((Set)((Map)openPackages).computeIfAbsent("sun.tools.attach", (k) -> {
                        return new HashSet();
                    })).add(currentModule);
                } catch (Exception var5) {
                    throw new RuntimeException("Failed to open module", var5);
                }
            }
        }
    }

    public static void loadAgent(String path) {
        try {
            openAttachModule();
            enableSelfAttach();
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            VirtualMachine vm = VirtualMachine.attach(pid);
            if (path.startsWith("/") && !FileSystems.getDefault().getSeparator().equals("/")) {
                path = path.substring(1);
            }

            vm.loadAgent(path);
            vm.detach();
        } catch (Exception var3) {
            var3.printStackTrace();
        }

    }

    public static void loadJVMTIAgent(String path) {
        try {
            openAttachModule();
            enableSelfAttach();
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            VirtualMachine vm = VirtualMachine.attach(pid);
            if (path.startsWith("/") && !FileSystems.getDefault().getSeparator().equals("/")) {
                path = path.substring(1);
            }

            vm.loadAgentPath(path);
            vm.detach();
        } catch (Exception var3) {
            var3.printStackTrace();
        }

    }

    public static void enableNativeAccess(Logger logger) {
        Module module = UnsafeUtils.class.getModule();
        try {

            if (lookup != null) {
                MethodHandle moduleForNativeAccessHandle = lookup.findVirtual(Module.class,"moduleForNativeAccess",MethodType.methodType(Module.class));

                if(enableNativeAccessClazz==null) enableNativeAccessClazz = Class.forName("java.lang.Module$EnableNativeAccess");
                MethodHandle isNativeAccessEnabledHandle = lookup.findStatic(enableNativeAccessClazz,"isNativeAccessEnabled",MethodType.methodType(boolean.class,Module.class));

                Module target = (Module) moduleForNativeAccessHandle.invokeExact(module);
                if((boolean) isNativeAccessEnabledHandle.invokeExact(target)){
                    logger.warn("The module's native access had been enabled! module:{} target module:{}",module,target);
                }
                else{
                    enableNativeAccess0(target);
                }
            }
            else {
                throw new IllegalStateException("The MethodHandleLookup is null!");
            }
        } catch (Throwable var3) {
            throw new RuntimeException("Failed to enable native access for module: "+module,var3);
        }
    }

    public static void enableNativeAccess0(Module target) throws Throwable {
        if (lookup != null) {
            MethodHandle implAddEnableNativeAccessToAllUnnamedHandle = lookup.findStatic(Module.class,"implAddEnableNativeAccessToAllUnnamed",MethodType.methodType(void.class));

            if(enableNativeAccessClazz==null) enableNativeAccessClazz = Class.forName("java.lang.Module$EnableNativeAccess");
            MethodHandle trySetEnableNativeAccessHandle = lookup.findStatic(enableNativeAccessClazz,"trySetEnableNativeAccess",MethodType.methodType(boolean.class,Module.class));
            MethodHandle isNativeAccessEnabledHandle = lookup.findStatic(enableNativeAccessClazz,"isNativeAccessEnabled",MethodType.methodType(boolean.class,Module.class));

            implAddEnableNativeAccessToAllUnnamedHandle.invokeExact();
            trySetEnableNativeAccessHandle.invoke(target);
            if(!(boolean) isNativeAccessEnabledHandle.invokeExact(target)){
                throw new RuntimeException("Failed to try set enable native access for module: "+target);
            }

            Class<?> moduleBoot = Class.forName("jdk.internal.module.ModuleBootstrap");
            Class<?> javaLangAccess = Class.forName("jdk.internal.access.JavaLangAccess");

            MethodHandle addEnableNativeAccessHandle = lookup.findStatic(moduleBoot,"addEnableNativeAccess",MethodType.methodType(void.class, ModuleLayer.class));

            MethodHandle addEnableNativeAccessToAllUnnamedHandle = lookup.findVirtual(javaLangAccess,"addEnableNativeAccessToAllUnnamed",MethodType.methodType(void.class));
            MethodHandle addEnableNativeAccessJLAHandle = lookup.findVirtual(javaLangAccess,"addEnableNativeAccess",MethodType.methodType(Module.class,Module.class));
            Object JLA = getFieldValue(moduleBoot,"JLA",Object.class);

            if(target.getLayer()!=null)addEnableNativeAccessHandle.invokeExact(target.getLayer());
            addEnableNativeAccessToAllUnnamedHandle.invoke(JLA);
            addEnableNativeAccessJLAHandle.invoke(JLA,target);

            Arrays.stream(moduleBoot.getDeclaredFields()).filter(f->f.getName().equals("HAS_ENABLE_NATIVE_ACCESS_FLAG")).findAny().ifPresent((f)->{
                setFieldValueF(f,null,true);
                System.out.println("modified HAS_ENABLE_NATIVE_ACCESS_FLAG.");
            });
        }
        else {
            throw new IllegalStateException("The MethodHandleLookup is null!");
        }
    }

    public static void debugInfoNativeAccess(Logger logger){
        Module module = UnsafeUtils.class.getModule();
        try {

            if (lookup != null) {
                MethodHandle moduleForNativeAccessHandle = lookup.findVirtual(Module.class,"moduleForNativeAccess",MethodType.methodType(Module.class));

                if(enableNativeAccessClazz==null) enableNativeAccessClazz = Class.forName("java.lang.Module$EnableNativeAccess");
                MethodHandle isNativeAccessEnabledHandle = lookup.findStatic(enableNativeAccessClazz,"isNativeAccessEnabled",MethodType.methodType(boolean.class,Module.class));

                Module target = (Module) moduleForNativeAccessHandle.invokeExact(module);

                logger.info("===java.lang.Module$EnableNativeAccess::isNativeAccessEnabled===");
                logger.info("module self {} is native access enabled: {}",module,(boolean) isNativeAccessEnabledHandle.invokeExact(target));
                logger.info("module target {} is native access enabled: {}",target,(boolean) isNativeAccessEnabledHandle.invokeExact(target));

                // JDK 25 removed ModuleBootstrap.hasEnableNativeAccessFlag(), so keep this
                // diagnostic block best-effort instead of failing the whole method.
                try {
                    Class<?> moduleBoot = Class.forName("jdk.internal.module.ModuleBootstrap");
                    MethodHandle hasEnableNativeAccessFlagHandle = lookup.findStatic(moduleBoot,"hasEnableNativeAccessFlag",MethodType.methodType(boolean.class));

                    logger.info("jdk.internal.module.ModuleBootstrap::HAS_ENABLE_NATIVE_ACCESS_FLAG={}",hasEnableNativeAccessFlagHandle.invoke());
                    logger.info("jdk.internal.module.ModuleBootstrap::NATIVE_ACCESS_MODULES={}", Objects.nonNull(getFieldValue(moduleBoot, "NATIVE_ACCESS_MODULES", Set.class)));
                } catch (Throwable throwable) {
                    logger.info("jdk.internal.module.ModuleBootstrap native-access diagnostics unavailable on this JDK: {}", throwable.toString());
                }
            }
            else {
                throw new IllegalStateException("The MethodHandleLookup is null!");
            }
        } catch (Throwable var3) {
            throw new RuntimeException("Failed to log native access for module: "+module,var3);
        }
    }

    public static void copyProperties(Class<?> clazz, Object source, Object target) {
        try {
            Field[] fields = clazz.getDeclaredFields();
            AccessibleObject.setAccessible(fields, true);
            Field[] var4 = fields;
            int var5 = fields.length;

            for(int var6 = 0; var6 < var5; ++var6) {
                Field field = var4[var6];
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.set(target, field.get(source));
                }
            }

        } catch (IllegalAccessException var8) {
            IllegalAccessException e = var8;
            throw new RuntimeException(e);
        }
    }

    static {
        try {
            Class<?> internalUNSAFEClass = lookup.findClass("jdk.internal.misc.Unsafe");
            objectFieldOffsetInternal = lookup.findVirtual(internalUNSAFEClass, "objectFieldOffset", MethodType.methodType(Long.TYPE, Field.class)).bindTo(internalUNSAFE);
        } catch (Exception var1) {
            Exception e = var1;
            e.printStackTrace();
        }

    }
}
