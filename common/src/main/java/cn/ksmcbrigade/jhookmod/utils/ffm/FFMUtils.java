package cn.ksmcbrigade.jhookmod.utils.ffm;

import cn.ksmcbrigade.jhookmod.Constants;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 通过 FFM 直接调用 JVMTI，注册 {@code ClassFileLoadHook}，在类加载阶段改写字节码。
 *
 * <p>相比 Mixin 的优势：Mixin 只有在目标类被 mixin 配置命中时才有机会介入，而本类可以
 * 拦截<b>本次注册之后</b>加载的所有类；改写后通过 JVMTI 的 {@code Allocate} 回写，
 * 由 JVM 负责释放。</p>
 *
 * <p><b>局限</b>：ClassFileLoadHook 只对「注册之后」发生的类加载生效。注册前就已经加载的类
 * （例如 {@code jdk.internal.invoke.*}、{@code jdk.internal.reflect.*}）不会被拦截，
 * 需要额外调用 {@code RetransformClasses}（JVMTI 函数表下标 151）才能重新触发。</p>
 *
 * <p>本类中所有函数表下标与结构体布局均取自 JDK 自带 {@code include/jvmti.h}，
 * 并在 JDK 22 / Windows x64 上实测通过（{@code Allocate}/{@code SetEventCallbacks}/
 * {@code SetEventNotificationMode}/{@code ClassFileLoadHook} 回写全链路）。</p>
 */
public final class FFMUtils {

    private FFMUtils() {
    }

    // =====================================================================
    //  转换器注册表
    // =====================================================================

    /** 类加载阶段的字节码转换器。 */
    public interface ClassTransformer {
        /**
         * @param className 点分形式的类名，例如 {@code java.lang.String}
         * @param classBytes 原始类文件字节
         * @return 改写后的字节码；返回 {@code null} 或原数组表示不修改
         */
        byte[] transform(String className, byte[] classBytes);
    }

    private static final List<ClassTransformer> TRANSFORMERS = new CopyOnWriteArrayList<>();

    public static void addTransformer(ClassTransformer transformer) {
        if (transformer != null) {
            TRANSFORMERS.add(transformer);
        }
    }

    public static boolean removeTransformer(ClassTransformer transformer) {
        return TRANSFORMERS.remove(transformer);
    }

    public static void clearTransformers() {
        TRANSFORMERS.clear();
    }

    public static boolean isInjected() {
        return injected;
    }

    /**
     * 回调被 JVM 调用的次数。即使没有注册任何转换器也会累加，
     * 用来确认 ClassFileLoadHook 是否真的在生效（诊断用）。
     */
    public static long getHookInvocations() {
        return HOOK_INVOCATIONS.get();
    }

    // =====================================================================
    //  权威布局常量（来源：JDK include/jvmti.h）
    // =====================================================================

    // ---- JNIInvokeInterface_（JavaVM 函数表，0-based）----
    //   [0..2] reserved0..2   [3] DestroyJavaVM   [4] AttachCurrentThread
    //   [5] DetachCurrentThread   [6] GetEnv   [7] AttachCurrentThreadAsDaemon
    private static final int JVM_ATTACH_CURRENT_THREAD = 4;
    private static final int JVM_GET_ENV = 6;
    private static final int JVM_ATTACH_CURRENT_THREAD_AS_DAEMON = 7;
    private static final int JVM_TABLE_SLOTS = 8;

    // ---- jvmtiInterface_1_（jvmtiEnv 函数表，0-based）----
    private static final int JVMTI_SET_EVENT_NOTIFICATION_MODE = 1;
    private static final int JVMTI_ALLOCATE = 45;
    private static final int JVMTI_DEALLOCATE = 46;
    private static final int JVMTI_SET_EVENT_CALLBACKS = 121;
    private static final int JVMTI_TABLE_SLOTS = 156;

    // ---- jvmtiEventCallbacks 结构体 ----
    // JDK 21/22 的 jvmti.h 里共 39 个函数指针槽，ClassFileLoadHook 是第 5 个字段（下标 4）。
    // HotSpot 的 SetEventCallbacks 按 min(size, sizeof(struct)) 拷贝，因此传 312 在
    // 结构体变大的新 JDK 上同样安全（新字段保持 NULL）。
    private static final int CALLBACKS_FIELD_COUNT = 39;
    private static final int CALLBACKS_CLASS_FILE_LOAD_HOOK = 4;

    // ---- 事件与开关 ----
    private static final int JVMTI_EVENT_CLASS_FILE_LOAD_HOOK = 54;
    private static final int JVMTI_ENABLE = 1;

    // ---- GetEnv 版本 ----
    private static final int JVMTI_VERSION_INTERFACE_JVMTI = 0x30000000;
    private static final int JVMTI_VERSION_1_2 = 0x30010200;
    private static final int JVMTI_VERSION_1_0 = 0x30010000;

    private static final long ADDR = ValueLayout.ADDRESS.byteSize();

    // =====================================================================
    //  状态
    // =====================================================================

    /** upcall 桩必须活到进程结束，因此挂在共享 Arena 上并保持静态强引用。 */
    private static final Arena GLOBAL_ARENA = Arena.ofShared();

    private static MemorySegment callbacksStub;
    private static MemorySegment callbacksTable;
    private static MethodHandle allocateHandle;

    private static volatile boolean injected = false;
    private static volatile boolean attaching = false;

    /** 回调重入保护：hook 内部写日志可能触发新的类加载。 */
    private static final ThreadLocal<Boolean> IN_HOOK = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 回调调用计数，纯粹用于诊断。 */
    private static final java.util.concurrent.atomic.AtomicLong HOOK_INVOCATIONS =
            new java.util.concurrent.atomic.AtomicLong();

    // =====================================================================
    //  对外入口
    // =====================================================================

    /**
     * 探测 FFM 是否真的能发起原生调用（只在开发环境用于诊断）。
     */
    public static void debugNativeAccess() {
        try {
            Linker linker = Linker.nativeLinker();
            MemorySegment strlenAddr = linker.defaultLookup().find("strlen")
                    .orElseThrow(() -> new UnsatisfiedLinkError("strlen not found"));
            MethodHandle strlen = linker.downcallHandle(strlenAddr,
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                long len = (long) strlen.invokeExact(arena.allocateFrom("hello FFM-API!("));
                Constants.LOG.info("FFM native call OK, strlen={}", len);
            }
        } catch (Throwable t) {
            Constants.LOG.error("FFM native call is blocked", t);
        }
    }

    /**
     * 取得 JVMTI 环境并注册 {@code ClassFileLoadHook}。
     *
     * <p>出错时只记录日志并返回 {@code false}，不抛异常——本方法会在 Mixin 插件初始化阶段被调用，
     * 抛出会导致整个游戏启动失败。</p>
     *
     * @return 是否成功完成注入
     */
    /**
     * Gets the JVMTI environment and installs {@code ClassFileLoadHook}.
     *
     * <p>If the hook cannot be installed this throws. A mod whose whole purpose is hooking must not
     * silently start with a half-working hook — failing loudly is the only useful behaviour.</p>
     *
     * @throws IllegalStateException when the injection fails
     */
    public static void tryGetJavaVm() {
        if (!tryInject()) {
            throw new IllegalStateException(
                    "JVMTI ClassFileLoadHook injection failed.");
        }
    }

    private static synchronized boolean tryInject() {
        if (injected) {
            Constants.LOG.info("JVMTI ClassFileLoadHook already installed, skipping.");
            return true;
        }
        if (attaching) {
            return false;
        }
        attaching = true;
        try {
            Linker linker = Linker.nativeLinker();

            // ---------- 1. JNI_GetCreatedJavaVMs ----------
            MemorySegment getVMsAddr = resolveGetCreatedJavaVMs(linker);
            MethodHandle getVMs = linker.downcallHandle(getVMsAddr, FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            try (Arena tmp = Arena.ofConfined()) {
                MemorySegment vmBuf = tmp.allocate(ValueLayout.ADDRESS);
                MemorySegment nVMs = tmp.allocate(ValueLayout.JAVA_INT);
                int rc = (int) getVMs.invokeExact(vmBuf, 1, nVMs);
                if (rc != 0) {
                    Constants.LOG.error("JNI_GetCreatedJavaVMs failed, rc={}", rc);
                    return false;
                }
                MemorySegment javaVM = vmBuf.get(ValueLayout.ADDRESS, 0);
                if (javaVM.equals(MemorySegment.NULL)) {
                    Constants.LOG.error("JavaVM is NULL");
                    return false;
                }
                javaVM = javaVM.reinterpret(Long.MAX_VALUE);

                // HotSpot 返回的是 &main_vm（一个保存函数表指针的槽位），因此需要多解一层引用。
                MemorySegment invokeTable = functionTable(javaVM, "JavaVM", JVM_TABLE_SLOTS);
                if (invokeTable == null) {
                    return false;
                }

                // ---------- 2. 附加当前线程 ----------
                if (!attachCurrentThread(linker, javaVM, invokeTable)) {
                    Constants.LOG.warn("AttachCurrentThread failed, continuing anyway.");
                }

                // ---------- 3. GetEnv -> jvmtiEnv ----------
                MemorySegment jvmtiEnv = getJvmtiEnv(linker, javaVM, invokeTable, tmp);
                if (jvmtiEnv == null) {
                    return false;
                }

                // jvmtiEnv 同样是指向「保存函数表指针的槽位」的指针。
                MemorySegment jvmtiTable = functionTable(jvmtiEnv, "jvmtiEnv", JVMTI_TABLE_SLOTS);
                if (jvmtiTable == null) {
                    return false;
                }

                // ---------- 4. 注册 ClassFileLoadHook ----------
                if (!installHook(linker, jvmtiEnv, jvmtiTable)) {
                    return false;
                }

                injected = true;
                Constants.LOG.info("JVMTI ClassFileLoadHook installed; "
                        + "classes loaded from now on can be transformed.");
                return true;
            }
        } catch (Throwable t) {
            Constants.LOG.error("Failed to initialize JVMTI", t);
            return false;
        } finally {
            attaching = false;
        }
    }

    // =====================================================================
    //  JavaVM / jvmtiEnv 布局读取
    // =====================================================================

    /**
     * 从「槽位指针」解析出真正的函数表。
     *
     * <p>HotSpot 里 {@code JNI_GetCreatedJavaVMs} 写回的是 {@code &main_vm}，
     * {@code GetEnv} 写回的也是 {@code _jvmtiEnv*}（其首个成员是 {@code functions}），
     * 两者都指向一个保存函数表指针的槽位，所以要再解一层引用。
     * 若槽位的第 0 项为 NULL（保留位），说明当前实现是单层布局，直接用它当函数表。</p>
     */
    private static MemorySegment functionTable(MemorySegment handle, String what, int slots) {
        MemorySegment first = handle.get(ValueLayout.ADDRESS, 0);
        MemorySegment table;
        if (first.equals(MemorySegment.NULL)) {
            table = handle;
            Constants.LOG.debug("{} layout: single-level (handle is the function table)", what);
        } else {
            table = first;
            Constants.LOG.debug("{} layout: double-indirect (handle -> function table)", what);
        }
        table = table.reinterpret(slots * ADDR);
        if (table.get(ValueLayout.ADDRESS, JVM_GET_ENV * ADDR).equals(MemorySegment.NULL)
                && "JavaVM".equals(what)) {
            Constants.LOG.error("{} function table looks invalid (GetEnv is NULL)", what);
            return null;
        }
        return table;
    }

    private static boolean attachCurrentThread(Linker linker, MemorySegment javaVM, MemorySegment table) {
        for (int index : new int[]{JVM_ATTACH_CURRENT_THREAD_AS_DAEMON, JVM_ATTACH_CURRENT_THREAD}) {
            MemorySegment fn = table.get(ValueLayout.ADDRESS, index * ADDR);
            if (fn.equals(MemorySegment.NULL)) {
                continue;
            }
            try {
                MethodHandle attach = linker.downcallHandle(fn, FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                try (Arena tmp = Arena.ofConfined()) {
                    MemorySegment envPtr = tmp.allocate(ValueLayout.ADDRESS);
                    int rc = (int) attach.invokeExact(javaVM, envPtr, MemorySegment.NULL);
                    if (rc == 0) {
                        return true;
                    }
                    Constants.LOG.warn("AttachCurrentThread(index {}) rc={}", index, rc);
                }
            } catch (Throwable t) {
                Constants.LOG.warn("AttachCurrentThread(index {}) threw", index, t);
            }
        }
        return false;
    }

    private static MemorySegment getJvmtiEnv(Linker linker, MemorySegment javaVM,
                                             MemorySegment table, Arena tmp) {
        MemorySegment fn = table.get(ValueLayout.ADDRESS, JVM_GET_ENV * ADDR);
        if (fn.equals(MemorySegment.NULL)) {
            Constants.LOG.error("GetEnv is NULL");
            return null;
        }
        try {
            MethodHandle getEnv = linker.downcallHandle(fn, FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // 版本号编码是 0x30000000 | (JDK 主版本 << 16)，例如 JDK 21 -> 0x30150000。
            // 老版本 1.x 的 0x30010500 这种值并不存在，会被 JVM 判为 JVMTI_ERROR_VERSION。
            int current = JVMTI_VERSION_INTERFACE_JVMTI | (Runtime.version().feature() << 16);
            for (int version : new int[]{current, JVMTI_VERSION_1_2, JVMTI_VERSION_1_0}) {
                MemorySegment out = tmp.allocate(ValueLayout.ADDRESS);
                out.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                int rc = (int) getEnv.invokeExact(javaVM, out, version);
                if (rc == 0) {
                    MemorySegment env = out.get(ValueLayout.ADDRESS, 0);
                    if (!env.equals(MemorySegment.NULL)) {
                        Constants.LOG.info("GetEnv(0x{}) succeeded", Integer.toHexString(version));
                        return env.reinterpret(Long.MAX_VALUE);
                    }
                }
                Constants.LOG.debug("GetEnv(0x{}) rc={}", Integer.toHexString(version), rc);
            }
            Constants.LOG.error("GetEnv(JVMTI) failed for every candidate version");
            return null;
        } catch (Throwable t) {
            Constants.LOG.error("GetEnv threw", t);
            return null;
        }
    }

    // =====================================================================
    //  注册 ClassFileLoadHook
    // =====================================================================

    private static boolean installHook(Linker linker, MemorySegment jvmtiEnv, MemorySegment table)
            throws Throwable {
        long callbacksSize = CALLBACKS_FIELD_COUNT * ADDR;

        // ---- 1. upcall 桩 ----
        MethodHandle hookHandle = MethodHandles.lookup().findStatic(FFMUtils.class, "classFileLoadHook",
                MethodType.methodType(void.class,
                        MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
                        MemorySegment.class, MemorySegment.class, int.class,
                        MemorySegment.class, MemorySegment.class, MemorySegment.class));
        callbacksStub = linker.upcallStub(hookHandle, FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), GLOBAL_ARENA);

        // ---- 2. jvmtiEventCallbacks 结构体 ----
        callbacksTable = GLOBAL_ARENA.allocate(callbacksSize);
        callbacksTable.fill((byte) 0);
        callbacksTable.set(ValueLayout.ADDRESS,
                CALLBACKS_CLASS_FILE_LOAD_HOOK * ADDR, callbacksStub);

        // ---- 3. SetEventCallbacks ----
        MemorySegment setCallbacksFn = table.get(ValueLayout.ADDRESS, JVMTI_SET_EVENT_CALLBACKS * ADDR);
        if (setCallbacksFn.equals(MemorySegment.NULL)) {
            Constants.LOG.error("SetEventCallbacks is NULL");
            return false;
        }
        MethodHandle setCallbacks = linker.downcallHandle(setCallbacksFn, FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        int cbRc = (int) setCallbacks.invokeExact(jvmtiEnv, callbacksTable, (int) callbacksSize);
        if (cbRc != 0) {
            Constants.LOG.error("SetEventCallbacks failed, rc={}", cbRc);
            return false;
        }

        // ---- 4. 预热 Allocate（hook 里不能再去查表/取句柄）----
        MemorySegment allocateFn = table.get(ValueLayout.ADDRESS, JVMTI_ALLOCATE * ADDR);
        if (allocateFn.equals(MemorySegment.NULL)) {
            Constants.LOG.error("Allocate is NULL");
            return false;
        }
        allocateHandle = linker.downcallHandle(allocateFn, FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

        // ---- 5. SetEventNotificationMode(ENABLE, 54) ----
        // ClassFileLoadHook 不需要任何 capability，可直接启用。
        MemorySegment setNotifyFn = table.get(ValueLayout.ADDRESS, JVMTI_SET_EVENT_NOTIFICATION_MODE * ADDR);
        if (setNotifyFn.equals(MemorySegment.NULL)) {
            Constants.LOG.error("SetEventNotificationMode is NULL");
            return false;
        }
        MethodHandle setNotify = linker.downcallHandle(setNotifyFn, FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                Linker.Option.firstVariadicArg(3));
        int notifyRc = (int) setNotify.invokeExact(
                jvmtiEnv, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, MemorySegment.NULL);
        if (notifyRc != 0) {
            Constants.LOG.error("SetEventNotificationMode(ENABLE, {}) failed, rc={}",
                    JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, notifyRc);
            return false;
        }
        return true;
    }

    // =====================================================================
    //  JVMTI 回调：类加载钩子
    // =====================================================================

    /**
     * JVMTI {@code ClassFileLoadHook} 回调。
     *
     * <p>注意：这是被 JVM 原生代码直接调用的 upcall 桩，任何逃逸的异常都会导致进程 abort，
     * 因此整个方法体必须包在 try/catch 里。</p>
     */
    /**
     * 回调出口：{@code (internalName, jclass)}。JHookUtils 用它把「正在被重定义」的类
     * 立刻提升为全局引用。
     *
     * <p>为什么只有这个时机安全：{@code class_being_redefined} 的 handle 属于 **VM 的回调帧**，
     * 回调返回前一直有效；而 FFM 每次下行调用都是独立原生入口，从别处拿到的 handle
     * 一旦跨调用就失效。所以这是唯一能合法 {@code NewGlobalRef} 的地方。</p>
     */
    public static volatile java.util.function.BiConsumer<String, Long> REDEFINE_SINK;

    static void classFileLoadHook(
            MemorySegment jvmtiEnv, MemorySegment jniEnv,
            MemorySegment classBeingRedefined, MemorySegment loader,
            MemorySegment name, MemorySegment protectionDomain,
            int classDataLen, MemorySegment classData,
            MemorySegment newClassDataLen, MemorySegment newClassData) {

        // 计数必须放在短路检查之前，否则没有转换器时无法确认回调是否被调用。
        HOOK_INVOCATIONS.incrementAndGet();

        // 在最早的短路之前：重定义回调是唯一能安全提升 class handle 的时机。
        if (REDEFINE_SINK != null
                && !classBeingRedefined.equals(MemorySegment.NULL)
                && !name.equals(MemorySegment.NULL)) {
            try {
                String internal = name.reinterpret(Long.MAX_VALUE).getString(0);
                if (!internal.isEmpty()) {
                    REDEFINE_SINK.accept(internal, classBeingRedefined.address());
                }
            } catch (Throwable ignored) {
                // upcall 边界内绝不能抛出
            }
        }

        // 最快的拒绝路径：没注册转换器就什么都不做，避免为每个类付出代价。
        if (TRANSFORMERS.isEmpty() || IN_HOOK.get()) {
            return;
        }
        IN_HOOK.set(Boolean.TRUE);
        try {
            if (name.equals(MemorySegment.NULL)
                    || classData.equals(MemorySegment.NULL)
                    || newClassDataLen.equals(MemorySegment.NULL)
                    || newClassData.equals(MemorySegment.NULL)
                    || classDataLen <= 0) {
                return;
            }

            String internalName = name.reinterpret(Long.MAX_VALUE).getString(0);
            if (internalName.isEmpty()) {
                return;
            }
            String className = internalName.replace('/', '.');
            byte[] bytes = classData.reinterpret(classDataLen).toArray(ValueLayout.JAVA_BYTE);

            boolean changed = false;
            for (ClassTransformer transformer : TRANSFORMERS) {
                try {
                    byte[] result = transformer.transform(className, bytes);
                    if (result != null && result != bytes) {
                        bytes = result;
                        changed = true;
                    }
                } catch (Throwable t) {
                    Constants.LOG.error("Transformer failed on {}", className, t);
                }
            }
            if (!changed || bytes == null || bytes.length == 0) {
                return;
            }

            // 回写必须用 JVMTI Allocate 分配内存：JVM 之后会用 Deallocate 释放它，
            // 换成 malloc 会破坏堆。
            try (Arena tmp = Arena.ofConfined()) {
                MemorySegment out = tmp.allocate(ValueLayout.ADDRESS);
                out.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                int rc = (int) allocateHandle.invokeExact(jvmtiEnv, (long) bytes.length, out);
                if (rc != 0) {
                    Constants.LOG.error("Allocate({}) failed for {}, rc={}", bytes.length, className, rc);
                    return;
                }
                MemorySegment buf = out.get(ValueLayout.ADDRESS, 0);
                if (buf.equals(MemorySegment.NULL)) {
                    return;
                }
                MemorySegment.copy(bytes, 0, buf.reinterpret(bytes.length),
                        ValueLayout.JAVA_BYTE, 0, bytes.length);

                newClassDataLen.reinterpret(ValueLayout.JAVA_INT.byteSize())
                        .set(ValueLayout.JAVA_INT, 0, bytes.length);
                newClassData.reinterpret(ADDR)
                        .set(ValueLayout.ADDRESS, 0, buf);
            }
            Constants.LOG.debug("Transformed {} ({} bytes)", className, bytes.length);
        } catch (Throwable t) {
            // 绝不能让异常穿过 upcall 边界
            Constants.LOG.error("ClassFileLoadHook error", t);
        } finally {
            IN_HOOK.set(Boolean.FALSE);
        }
    }

    // =====================================================================
    //  跨平台符号解析
    // =====================================================================

    private static MemorySegment resolveGetCreatedJavaVMs(Linker linker) throws Throwable {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            SymbolLookup kernel32;
            try {
                kernel32 = SymbolLookup.libraryLookup("kernel32", GLOBAL_ARENA);
            } catch (Throwable t) {
                String systemRoot = System.getenv("SystemRoot");
                if (systemRoot == null) {
                    systemRoot = "C:\\Windows";
                }
                kernel32 = SymbolLookup.libraryLookup(
                        Path.of(systemRoot, "System32", "kernel32.dll"), GLOBAL_ARENA);
            }
            SymbolLookup lookup = kernel32
                    .or(SymbolLookup.loaderLookup())
                    .or(linker.defaultLookup());

            MethodHandle getModuleHandleW = linker.downcallHandle(
                    lookup.find("GetModuleHandleW").orElseThrow(
                            () -> new UnsatisfiedLinkError("GetModuleHandleW not found")),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle getProcAddress = linker.downcallHandle(
                    lookup.find("GetProcAddress").orElseThrow(
                            () -> new UnsatisfiedLinkError("GetProcAddress not found")),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            MemorySegment hModule;
            try (Arena local = Arena.ofConfined()) {
                hModule = (MemorySegment) getModuleHandleW.invokeExact(toWideString(local, "jvm.dll"));
            }
            if (hModule.equals(MemorySegment.NULL)) {
                throw new UnsatisfiedLinkError("jvm.dll is not loaded in this process");
            }
            MemorySegment procName = GLOBAL_ARENA.allocateFrom("JNI_GetCreatedJavaVMs");
            MemorySegment addr = (MemorySegment) getProcAddress.invokeExact(hModule, procName);
            if (addr.equals(MemorySegment.NULL)) {
                throw new UnsatisfiedLinkError("JNI_GetCreatedJavaVMs not exported by jvm.dll");
            }
            return addr;
        }

        String javaHome = System.getProperty("java.home");
        String libName = os.contains("mac") ? "libjvm.dylib" : "libjvm.so";
        Path libjvmPath = Path.of(javaHome, "lib", "server", libName);
        SymbolLookup jvmLookup = SymbolLookup.libraryLookup(libjvmPath, GLOBAL_ARENA);
        return jvmLookup.find("JNI_GetCreatedJavaVMs").orElseThrow(
                () -> new UnsatisfiedLinkError("JNI_GetCreatedJavaVMs not found in " + libjvmPath));
    }

    private static MemorySegment toWideString(Arena arena, String s) {
        MemorySegment seg = arena.allocate(ValueLayout.JAVA_CHAR, s.length() + 1);
        for (int i = 0; i < s.length(); i++) {
            seg.setAtIndex(ValueLayout.JAVA_CHAR, i, s.charAt(i));
        }
        seg.setAtIndex(ValueLayout.JAVA_CHAR, s.length(), (char) 0);
        return seg;
    }
}
