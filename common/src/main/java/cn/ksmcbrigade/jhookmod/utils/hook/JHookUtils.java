package cn.ksmcbrigade.jhookmod.utils.hook;

import cn.ksmcbrigade.jhookmod.CommonClass;
import cn.ksmcbrigade.jhookmod.Constants;
import cn.ksmcbrigade.jhookmod.utils.agent.UnsafeUtils;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 不改类字节码的方法级 Hook。
 *
 * <p>核心：HotSpot 里一个 Java 方法对应一个 C++ 的 {@code Method} 对象，真正决定「执行哪段字节码」
 * 的是它的 {@code Method::_constMethod}。把目标方法的 {@code _constMethod} 换成另一个方法的，
 * 目标方法就会执行对方的字节码，而 class 文件一个字节都没动过。</p>
 *
 * <p>因为描述符（以及 static/实例性质）必须一致，调用约定的局部变量表布局就完全一致，
 * 所以 <b>接收者（{@code this}）和参数由调用方原样透传</b>，hook 里拿到的就是调用方那个对象。</p>
 *
 * <p>{@code Method::name()} 读的是 {@code ConstMethod::_name_index}，换 {@code _constMethod}
 * 会把名字一起换掉，导致原本的名字在类里消失、新的调用点解析时抛 {@code NoSuchMethodError}。
 * 本类会把名字索引改回目标方法的名字来消除这个副作用。注意索引必须属于 <b>{@code ConstMethod}
 * 所在的常量池</b>，跨类时需要在该类常量池里能找到同名的 UTF8 项（用 JVMTI
 * {@code GetConstantPool} 扫描原始常量池字节），找不到就退化为「只 Hook、不保名字」并给出告警。</p>
 */
public final class JHookUtils {

    private JHookUtils() {
    }

    /** 一次成功的 Hook 记录，供 {@link #unhook} 还原。 */
    private record HookRecord(long targetMethod,
                              long targetConstBefore,
                              long hookConst,
                              int hookConstNameIndexBefore,
                              long elevatedHookCp,
                              long originalHookCpHolder) {
    }

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_PROTECTED = 0x0004;

    private static final Map<String, HookRecord> RECORDS = new ConcurrentHashMap<>();

    /** 关闭 deopt 的排查开关：{@code -Djhook.noDeopt=true}。 */
    private static final boolean DEOPT = !Boolean.getBoolean("jhook.noDeopt");

    /** 每个类只 retransform 一次（类重定义成本不低，同一类装多个 hook 时没必要重复）。 */
    private static final java.util.Set<Class<?>> RETRANSFORMED_CLASSES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // =================================================================
    //  对外 API
    // =================================================================

    /**
     * 让 {@code originalM} 的调用执行 {@code hookM} 的字节码。
     *
     * @param originalClazz 目标方法所在的类（用于解析 jclass）
     * @param originalM     被替换的方法
     * @param hookClazz     hook 方法所在的类
     * @param hookM         替换体；描述符与 static 性质必须与 {@code originalM} 一致
     */
    public static void hookPublic(Class<?> originalClazz, Method originalM,
                                  Class<?> hookClazz, Method hookM) {
        hookWithBackup(originalClazz, originalM, hookClazz, hookM, null, null);
    }

    /**
     * 允许「跨类 Hook 非 public 方法」。
     *
     * <p>做法：把 hook 方法的 {@code ConstantPool} <b>复制一份</b>，只改副本的
     * {@code _pool_holder} 指向目标类，再让 hook 的 {@code ConstMethod} 指向副本。
     * 于是 {@code method_holder()} 报的是目标类，链接期 private/包级访问检查成立，
     * 而 hook 类自己的常量池<b>完全没被改动</b>（这是早期直接改共用常量池会崩的原因）。</p>
     *
     * <p><b>使用约束</b>：hook 方法体只能引用 public 成员或编译期常量——它的引用会按
     * 「从目标类发起」检查可见性。</p>
     */
    private static boolean typeDumped;

    /**
     * Read-only diagnostic: dumps the HotSpot field layout of one method and the values found
     * there. Nothing is written, so it is safe on a live process.
     *
     * <p>Used to find where {@code Method::_flags} lives and which bits are set, before deciding
     * how to stop the JIT from compiling swapped methods.</p>
     */
    public static void probeMethodLayout(Class<?> clazz, Method method) {
        try {
            Natives n = Natives.get();
            long addr = n.methodAddress(clazz, method);
            if (!typeDumped) {
                typeDumped = true;
                n.probeCapabilities();
                Constants.LOG.info("[JHookProbe] Method layout: {}", n.fieldDump("Method"));
                Constants.LOG.info("[JHookProbe] ConstMethod layout: {}", n.fieldDump("ConstMethod"));
            }
            Constants.LOG.info("[JHookProbe] {}.{} Method*=0x{}",
                    clazz.getName(), method.getName(), Long.toHexString(addr));

            dumpFields(n, "Method", addr, new String[]{
                    "_constMethod", "_constants", "_access_flags", "_flags",
                    "_code", "_i2i_entry", "_from_compiled_entry", "_from_interpreted_entry",
                    "_vtable_index", "_method_size", "_intrinsic_id"});

            long constMethod = n.readPtr(addr + n.offset("Method", "_constMethod"));
            Constants.LOG.info("[JHookProbe]   ConstMethod*=0x{}", Long.toHexString(constMethod));
            dumpFields(n, "ConstMethod", constMethod, new String[]{
                    "_constants", "_name_index", "_signature_index",
                    "_flags", "_max_stack", "_max_locals", "_size", "_code_size"});
        } catch (Throwable t) {
            Constants.LOG.warn("[JHookProbe] failed for {}.{}", clazz.getName(), method.getName(), t);
        }
    }

    /** Read-only probe: locate {@code jvmtiCapabilities} inside the JVMTI env. */
    public static void probeCapabilities() {
        try {
            Natives.get().probeCapabilities();
        } catch (Throwable t) {
            Constants.LOG.warn("[JHookProbe] capability probe unavailable", t);
        }
    }

    private static void dumpFields(Natives n, String type, long base, String[] fields) {
        for (String field : fields) {
            // 逐字段独立容错：指针字段按 8 字节对齐读，整型字段按 4 字节对齐读。
            // 有些字段（如 Method::_access_flags）偏移不是 8 的倍数，用 readPtr 会因对齐失败。
            long offset;
            try {
                offset = n.offset(type, field);
            } catch (Throwable absent) {
                Constants.LOG.info("[JHookProbe]   {}::{} <absent from VMStructs>", type, field);
                continue;
            }
            String pointer;
            try {
                pointer = "0x" + Long.toHexString(n.readPtr(base + offset));
            } catch (Throwable unaligned) {
                pointer = "<unaligned>";
            }
            String value;
            try {
                int raw = n.readInt(base + offset);
                value = raw + " (" + Integer.toBinaryString(raw) + ")";
            } catch (Throwable unaligned) {
                value = "<unaligned>";
            }
            Constants.LOG.info("[JHookProbe]   {}::{} off={} ptr={} int={}", type, field, offset, pointer, value);
        }
    }

    public static void hookNonPublic(Class<?> originalClazz, Method originalM,
                                     Class<?> hookClazz, Method hookM) {
        hookImpl(originalClazz, originalM, hookClazz, hookM, null, null, true);
    }

    /**
     * 同 {@link #hookPublic}，额外把 {@code originalM} 的<b>原始实现</b>搬到 {@code backupM} 上，
     * 这样 hook 内部可以调用 {@code backupM} 来执行原版逻辑（环绕钩子）。
     *
     * <p><b>注意</b>：这个模式需要改写目标方法自己的 {@code ConstMethod}（把名字让给 backup），
     * 实测在部分 JVM 运行中会被解析器拒绝。本方法会在落笔后校验，
     * 失败时<b>自动回滚并抛异常</b>，不会留下损坏的类。对可靠性要求高的场景请用
     * {@link #hookPublic}。</p>
     *
     * @param backupClazz 备份方法所在的类（可为 {@code null}）
     * @param backupM     承接原始实现的方法；描述符必须与 {@code originalM} 一致
     */
    public static void hookWithBackup(Class<?> originalClazz, Method originalM,
                                      Class<?> hookClazz, Method hookM,
                                      Class<?> backupClazz, Method backupM) {
        hookImpl(originalClazz, originalM, hookClazz, hookM, backupClazz, backupM, false);
    }

    private static void hookImpl(Class<?> originalClazz, Method originalM,
                                 Class<?> hookClazz, Method hookM,
                                 Class<?> backupClazz, Method backupM,
                                 boolean allowAccessOverride) {
        if (originalClazz == null || originalM == null || hookClazz == null || hookM == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        try {
            validate(originalM, hookM, backupM);

            Natives n = Natives.get();
            long constOffset = n.offset("Method", "_constMethod");
            long nameOffset = n.offset("ConstMethod", "_name_index");
            long flagsOffset = n.offset("Method", "_access_flags");

            // 关键顺序：先 retransform 再装 hook。
            // RetransformClasses 是同步的，返回时该类的编译产物已被丢弃、内联依赖已失效，
            // 但方法元数据也被重建（所以必须在这之后重新取 Method* 地址）。
            // 之后若方法再次被 JIT 编译，C2 编译的是我们换上去的那份 ConstMethod，所以依然生效。
            //
            // 去重：同一个类只做一次；但如果目标方法此刻已经被编译过，就必须再刷新一次，
            // 否则它的 nmethod 会绕过 _constMethod。
            if (DEOPT) {
                long probe = n.methodAddress(originalClazz, originalM);
                // 注意：不要改用「清 _code + 重置 _from_compiled_entry」来 deopt。
                // 实测（26.2 / JDK 25）会让已编译的调用方跳进失效的 nmethod，返回值变成垃圾值，
                // 比「hook 不生效」危险得多。releaseCompiledCode 因此只在显式调用时才用。
                boolean firstTime = RETRANSFORMED_CLASSES.add(originalClazz);
                if (firstTime || n.isCompiled(probe)) {
                    n.deoptimize(originalClazz);
                }
            }

            long targetMethod = n.methodAddress(originalClazz, originalM);
            long hookMethod = n.methodAddress(hookClazz, hookM);

            // 跨类 Hook 非 public 方法时，链接期的访问检查会用 method_holder 判断可见性，
            // 而 method_holder = _constMethod->_constants->pool_holder，换完 ConstMethod 后
            // 它会变成 hook 所在的类，检查必然失败。
            // 访问标志不再读 Method::_access_flags：JDK 24+ 重构过 AccessFlags，
            // VMStructs 报的偏移在 JDK 25 上取到的是布尔化的值
            // （private / protected / public 全都读成 0x1），导致 private 被误判成 public。
            // java.lang.reflect.Method#getModifiers() 走的是 JNI GetMethodModifiers，
            // 位布局与 Modifier 常量完全一致，跨版本稳定。
            int targetModifiers = originalM.getModifiers();
            boolean needElevate = hookClazz != originalClazz
                    && (targetModifiers & ACC_PUBLIC) == 0;
            if (needElevate && !allowAccessOverride) {
                throw new IllegalArgumentException(String.format(
                        "Target method %s.%s is not public; cross-class hooking cannot be done safely ("
                                + "after swapping ConstMethod, method_holder becomes %s so the link-time access check must fail). "
                                + "Put the hook method inside the target class %s instead.",
                        originalClazz.getSimpleName(), originalM.getName(),
                        hookClazz.getSimpleName(), originalM.getDeclaringClass().getSimpleName()));
            }

            long targetConst = n.readPtr(targetMethod + constOffset);
            long hookConst = n.readPtr(hookMethod + constOffset);

            // 同类 hook 不能原地改 hook 方法的 ConstMethod：那样目标方法会和 hook 方法
            // 共享同一个 ConstMethod，类里出现两个同名同签名的方法，JDK 25 的方法解析
            // 会直接错乱（实测抛 NoSuchMethodError，指向完全无关的方法）。
            // 改成复制一份，只改副本。
            if (hookClazz == originalClazz) {
                hookConst = n.cloneConstMethod(hookConst);
            }

            int hookNameIndexBefore = n.readU2(hookConst + nameOffset);

            // 非 public 跨类：把 hook 方法那份常量池「复制一份」，只把副本的 pool_holder 改成
            // 目标类，再让 hook 的 ConstMethod 指向副本。这样：
            //   * method_holder() 报的是目标类 → private 访问检查成立；
            //   * hook 类自己的常量池完全没被改 → 不再污染共用路径。
            long copiedCp = 0;
            long sourceCp = 0;
            if (needElevate) {
                long elevated = n.copyConstantPool(hookConst, targetConst);
                if (elevated != 0) {
                    long constPoolOffset = n.offset("ConstMethod", "_constants");
                    sourceCp = n.readPtr(hookConst + constPoolOffset);
                    n.writePtr(hookConst + constPoolOffset, elevated);
                    copiedCp = elevated;
                    Constants.LOG.warn("[JHook] {}.{} is not public: switched to a copied constant pool 0x{}"
                                    + " (pool_holder points at the target class, original pool untouched)",
                            originalClazz.getSimpleName(), originalM.getName(),
                            Long.toHexString(elevated));
                } else {
                    Constants.LOG.warn("[JHook] failed to copy the constant pool of {}; non-public cross-class hooking may fail",
                            originalClazz.getSimpleName());
                }
            }

            long backupMethod = 0;
            long backupConst = 0;
            if (backupM != null) {
                backupMethod = n.methodAddress(backupClazz, backupM);
                backupConst = n.readPtr(backupMethod + constOffset);
            }

            // ---- 先把两个名字索引都解析出来（只读），全部成功才动内存 ----
            // 名字索引必须属于对应 ConstMethod 所在的常量池：
            //   * 同一个类 → 直接借用同名字段所在方法的索引；
            //   * 跨类 → 只能在目标常量池里现找同名的 UTF8 项。
            int hookNameIndex = n.resolveNameIndex(nameOffset, targetConst,
                    hookClazz == originalClazz, hookClazz, originalM.getName());
            if (hookNameIndex <= 0) {
                throw new IllegalStateException(String.format(
                        "Cannot preserve the name %s.%s: no matching UTF8 entry in the constant pool of %s. "
                                + "For cross-class hooking, make the hook class reference that name at least once "
                                + "(e.g. a same-text string constant), or put the hook method inside the target class.",
                        originalClazz.getSimpleName(), originalM.getName(),
                        hookClazz.getSimpleName()));
            }

            int backupNameIndex = 0;
            if (backupM != null) {
                backupNameIndex = n.resolveNameIndex(nameOffset, backupConst,
                        backupClazz == originalClazz, originalClazz, backupM.getName());
                if (backupNameIndex <= 0) {
                    throw new IllegalStateException(String.format(
                            "Cannot attach the original body to %s.%s: no matching UTF8 entry in the constant pool of %s.",
                            originalClazz.getSimpleName(), backupM.getName(),
                            originalClazz.getSimpleName()));
                }
            }

            // ---- 解析都成功，开始落笔；先记旧值，校验失败要回滚 ----
            int oldHookNameIndex = n.readU2(hookConst + nameOffset);
            int oldTargetNameIndex = n.readU2(targetConst + nameOffset);
            long oldTargetConst = targetConst;
            long oldBackupConst = backupConst;

            n.writeU2(hookConst + nameOffset, hookNameIndex);
            if (backupM != null) {
                n.writeU2(targetConst + nameOffset, backupNameIndex);
            }
            n.writePtr(targetMethod + constOffset, hookConst);
            if (backupM != null) {
                n.writePtr(backupMethod + constOffset, targetConst);
            }

            // ---- 立笔后立刻让 VM 自己按名字查一遍，确认解析路径真的能看到新状态 ----
            // 这一步很重要：JVM 偶尔会拒绝一个刚改过名字的方法（原因尚未完全定位），
            // 若不校验就会表现为运行期 NoSuchMethodError + 类处于损坏状态。
            boolean resolvable = n.canResolve(originalClazz, originalM)
                    && (backupM == null || n.canResolve(backupClazz, backupM));
            if (!resolvable) {
                n.writeU2(hookConst + nameOffset, oldHookNameIndex);
                n.writeU2(targetConst + nameOffset, oldTargetNameIndex);
                n.writePtr(targetMethod + constOffset, oldTargetConst);
                if (backupM != null) {
                    n.writePtr(backupMethod + constOffset, oldBackupConst);
                }
                throw new IllegalStateException(String.format(
                        "The JVM did not recognise the rewritten method name; rolled back: %s.%s (hook from %s.%s). "
                                + "Use hookPublic() without backup, or put the hook method inside the target class.",
                        originalClazz.getSimpleName(), originalM.getName(),
                        hookClazz.getSimpleName(), hookM.getName()));
            }

            RECORDS.put(key(originalClazz, originalM),
                    new HookRecord(targetMethod, targetConst, hookConst, hookNameIndexBefore,
                            copiedCp, sourceCp));

            // 这里刻意不调用 deoptimize：实测 RetransformClasses 会把已编译方法上的
            // _constMethod 改写抹掉，反而让 Hook 在热点方法上失效。deopt 只在 unhook 后用。

            Constants.LOG.info("[JHook] hooked {}.{}{} -> {}.{}{}",
                    originalClazz.getSimpleName(), originalM.getName(), n.describe(originalM),
                    hookClazz.getSimpleName(), hookM.getName(), n.describe(hookM));

            Constants.LOG.info("[JHook] hooked {}.{}{} -> {}.{}{}",
                    originalClazz.getSimpleName(), originalM.getName(), n.describe(originalM),
                    hookClazz.getSimpleName(), hookM.getName(), n.describe(hookM));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Hook failed: " + originalClazz.getName()
                    + "#" + originalM.getName(), t);
        }
    }

    /**
     * 还原一次 {@link #hookPublic}。
     *
     * @return 是否成功还原（没有记录时返回 {@code false}）
     */
    public static boolean unhook(Class<?> originalClazz, Method originalM) {
        HookRecord record = RECORDS.remove(key(originalClazz, originalM));
        if (record == null) {
            return false;
        }
        try {
            Natives n = Natives.get();
            long constOffset = n.offset("Method", "_constMethod");
            long nameOffset = n.offset("ConstMethod", "_name_index");

            n.writePtr(record.targetMethod() + constOffset, record.targetConstBefore());
            n.writeU2(record.hookConst() + nameOffset, record.hookConstNameIndexBefore());
            if (record.elevatedHookCp() != 0) {
                long constPoolOffset = n.offset("ConstMethod", "_constants");
                n.writePtr(record.hookConst() + constPoolOffset, record.originalHookCpHolder());
            }
            if (DEOPT) { n.deoptimize(originalClazz); }
            Constants.LOG.info("[JHook] unhooked {}.{}", originalClazz.getSimpleName(),
                    originalM.getName());
            return true;
        } catch (Throwable t) {
            Constants.LOG.error("[JHook] restore failed", t);
            return false;
        }
    }

    // =================================================================
    //  校验与描述符
    // =================================================================

    private static void validate(Method originalM, Method hookM, Method backupM) {
        if (!descriptor(originalM).equals(descriptor(hookM))) {
            throw new IllegalArgumentException("Descriptor mismatch: " + descriptor(originalM)
                    + " vs " + descriptor(hookM));
        }
        if (Modifier.isStatic(originalM.getModifiers()) != Modifier.isStatic(hookM.getModifiers())) {
            throw new IllegalArgumentException("static-ness must match, otherwise local variable slots shift and the JVM will crash");
        }
        if (backupM != null) {
            if (!descriptor(originalM).equals(descriptor(backupM))) {
                throw new IllegalArgumentException("backup descriptor must match the target method");
            }
            if (Modifier.isStatic(originalM.getModifiers()) != Modifier.isStatic(backupM.getModifiers())) {
                throw new IllegalArgumentException("backup static-ness must match the target method");
            }
        }
    }

    static String descriptor(Method method) {
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

    private static String key(Class<?> clazz, Method method) {
        return clazz.getName() + "#" + method.getName() + descriptor(method);
    }

    // =================================================================
    //  原生侧
    // =================================================================

    /**
     * 符号解析 + VMStructs + JNI/JVMTI。
     *
     * <p>字段偏移不硬编码：从 JVM 自己导出的 {@code gHotSpotVMStructs} 表里按
     * 「类型名 + 字段名」查（{@code Method::_constMethod}、{@code ConstMethod::_name_index}），
     * 换 JDK 版本不会错位。</p>
     */
    private static final class Natives {

        // jvmtiInterface_1_ 下标（由 JDK 的 include/jvmti.h 解析得到）
        private static final int JVMTI_DEALLOCATE = 46;
        private static final int JVMTI_GET_CLASS_SIGNATURE = 47;
        private static final int JVMTI_GET_LOADED_CLASSES = 77;
        private static final int JVMTI_GET_CONSTANT_POOL = 145;
        private static final int JVMTI_ADD_CAPABILITIES = 141;
        private static final int JVMTI_RETRANSFORM_CLASSES = 151;
        private static final int JVMTI_SET_BREAKPOINT = 37;
        private static final int JVMTI_CLEAR_BREAKPOINT = 38;
        private static final int JVMTI_TABLE_SLOTS = 156;

        /** can_generate_breakpoint_events：bit 19，byte 0 的 0x00080000。 */
        private static final int CAP_BREAKPOINT = 0x00080000;
        private boolean breakpointTried;
        private boolean breakpointReady;

        /** jvmtiCapabilities 的 can_get_constant_pool：第 35 位，即 byte 4 的 0x08。 */
        private static final int CAP_CAN_GET_CONSTANT_POOL = 0x08;
        /** jvmtiCapabilities 的 can_retransform_classes：第 37 位，即 byte 4 的 0x20。 */
        private static final int CAP_CAN_RETRANSFORM_CLASSES = 0x20;

        private boolean capabilityTried;
        private boolean capabilityReady;
        private boolean retransformTried;
        private boolean retransformReady;

        // JNINativeInterface_ 下标（由 include/jni.h 解析得到）
        private static final int JNI_GET_METHOD_ID = 33;
        private static final int JNI_GET_STATIC_METHOD_ID = 113;
        private static final int JNI_CALL_INT_METHOD_A = 51;
        private static final int JNI_TABLE_SLOTS = 235;

        private static final Linker LINKER = Linker.nativeLinker();
        private static final Arena ARENA = Arena.ofShared();
        private static final long P = ValueLayout.ADDRESS.byteSize();

        private static volatile Natives instance;

        private MemorySegment jvmModule;
        private MemorySegment javaVM;
        private MemorySegment invokeTable;
        private MemorySegment jniEnv;
        private MemorySegment jniTable;
        private MemorySegment jvmtiEnv;
        private MemorySegment jvmtiTable;
        private MemorySegment structsBase;
        private long typeNameOff;
        private long fieldNameOff;
        private long offsetOff;
        private long stride;

        static synchronized Natives get() throws Throwable {
            if (instance == null) {
                ensureNativeAccess();
                Natives n = new Natives();
                n.init();
                instance = n;
            }
            return instance;
        }

        /** 结合 {@link UnsafeUtils}：FFM 的受限方法要求所在模块开启 native access。 */
        private static void ensureNativeAccess() {
            try {
                UnsafeUtils.enableNativeAccess(Constants.LOG);
            } catch (Throwable t) {
                Constants.LOG.warn("[JHook] failed to enable native access; FFM calls may be rejected", t);
            }
        }

        private void init() throws Throwable {
            jvmModule = moduleHandle("jvm.dll");

            typeNameOff = readU64Symbol("gHotSpotVMStructEntryTypeNameOffset");
            fieldNameOff = readU64Symbol("gHotSpotVMStructEntryFieldNameOffset");
            offsetOff = readU64Symbol("gHotSpotVMStructEntryOffsetOffset");
            stride = readU64Symbol("gHotSpotVMStructEntryArrayStride");
            structsBase = symbol("gHotSpotVMStructs")
                    .get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

            MethodHandle getVMs = LINKER.downcallHandle(symbol("JNI_GetCreatedJavaVMs"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MemorySegment vmBuf = ARENA.allocate(ValueLayout.ADDRESS);
            MemorySegment nVMs = ARENA.allocate(ValueLayout.JAVA_INT);
            if ((int) getVMs.invokeExact(vmBuf, 1, nVMs) != 0) {
                throw new IllegalStateException("JNI_GetCreatedJavaVMs failed");
            }
            javaVM = vmBuf.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            invokeTable = functionTable(javaVM, 8);

            jniEnv = attachCurrentThread();
            jniTable = functionTable(jniEnv, JNI_TABLE_SLOTS);
            jvmtiEnv = getJvmtiEnv();
            jvmtiTable = functionTable(jvmtiEnv, JVMTI_TABLE_SLOTS);
        }

        /** 这台 JVM 上 {@code JavaVM} / {@code jvmtiEnv} 都指向「保存函数表的槽位」，要多解一层。 */
        private static MemorySegment functionTable(MemorySegment handle, int slots) {
            MemorySegment first = handle.get(ValueLayout.ADDRESS, 0);
            MemorySegment table = first.equals(MemorySegment.NULL) ? handle : first;
            return table.reinterpret(slots * P);
        }

        long offset(String type, String field) {
            for (int i = 0; i < 200_000; i++) {
                MemorySegment e = structsBase.asSlice(i * stride, stride);
                MemorySegment typeName = e.get(ValueLayout.ADDRESS, typeNameOff);
                if (typeName.equals(MemorySegment.NULL)) {
                    break;
                }
                if (!cstr(typeName).equals(type)) {
                    continue;
                }
                if (cstr(e.get(ValueLayout.ADDRESS, fieldNameOff)).equals(field)) {
                    return e.get(ValueLayout.JAVA_LONG, offsetOff);
                }
            }
            throw new IllegalStateException("VMStructs entry not found: " + type + "::" + field);
        }

        /** 列出某个 VMStructs 类型的全部字段（只读诊断，用来确认字段布局）。 */
        String fieldDump(String type) {
            StringBuilder out = new StringBuilder();
            long typeStringOff = -1;
            try {
                typeStringOff = readU64Symbol("gHotSpotVMStructEntryTypeStringOffset");
            } catch (Throwable ignored) {
                // 某些 JDK 不导出这个符号，退化为只打印名字和偏移
            }
            for (int i = 0; i < 200_000; i++) {
                MemorySegment e = structsBase.asSlice(i * stride, stride);
                MemorySegment typeName = e.get(ValueLayout.ADDRESS, typeNameOff);
                if (typeName.equals(MemorySegment.NULL)) {
                    break;
                }
                if (!cstr(typeName).equals(type)) {
                    continue;
                }
                MemorySegment fieldName = e.get(ValueLayout.ADDRESS, fieldNameOff);
                out.append(cstr(fieldName));
                if (typeStringOff >= 0) {
                    out.append(':').append(cstr(e.get(ValueLayout.ADDRESS, typeStringOff)));
                }
                out.append('@').append(e.get(ValueLayout.JAVA_LONG, offsetOff)).append("  ");
            }
            return out.toString();
        }

        // ---------- Method* ----------

        /**
         * 取 {@code Method} 对应的 C++ {@code Method*} 地址。
         * <p>{@code GetMethodID}/{@code GetStaticMethodID} 返回的是指向 methods 数组某一格的指针，
         * 真正的 {@code Method*} 要再解一层引用。</p>
         */
        long methodAddress(Class<?> clazz, Method method) throws Throwable {
            return readPtr(rawMethodId(clazz, method));
        }

        /** 该方法是否已有编译产物（{@code Method::_code} 非空即表示有 nmethod）。 */
        boolean isCompiled(long methodAddr) {
            try {
                return readPtr(methodAddr + offset("Method", "_code")) != 0;
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * 丢弃一个方法已编译的代码：{@code _code = 0}，且把 {@code _from_compiled_entry}
         * 指回 {@code _i2i_entry}。
         *
         * <p>i2i adapter 每次调用都会重新读 {@code _constMethod}，所以之后调用会看到我们换上去的
         * 字节码。局限：已经把这个方法内联进去的调用方不会因此失效，那需要 VM 级 deopt。</p>
         */
        void releaseCompiledCode(long methodAddr) {
            try {
                writePtr(methodAddr + offset("Method", "_code"), 0);
            } catch (Throwable t) {
                Constants.LOG.debug("[JHook] cannot clear Method::_code", t);
            }
            try {
                long i2i = readPtr(methodAddr + offset("Method", "_i2i_entry"));
                writePtr(methodAddr + offset("Method", "_from_compiled_entry"), i2i);
            } catch (Throwable t) {
                Constants.LOG.debug("[JHook] cannot reset Method::_from_compiled_entry", t);
            }
        }

        /**
         * 把一个 {@link cn.ksmcbrigade.jhookmod.utils.hook.JHookUtils} {@code ConstMethod}
         * 复制到原生内存，返回副本地址。
         *
         * <p>大小取自 {@code ConstMethod::_constMethod_size}（字数），所以字节码等尾部数组
         * 会一起被复制；副本与原对象字节一致，只有我们主动改的字段不同。</p>
         */
        long cloneConstMethod(long constMethod) throws Throwable {
            int words = readInt(constMethod + offset("ConstMethod", "_constMethod_size"));
            if (words <= 0 || words > 65_536) {
                throw new IllegalStateException("unexpected ConstMethod size: " + words);
            }
            long bytes = words * 8L;
            MemorySegment source = MemorySegment.ofAddress(constMethod).reinterpret(bytes);
            MemorySegment copy = ARENA.allocate(bytes, 8);
            MemorySegment.copy(source, 0, copy, 0, bytes);
            return copy.address();
        }

        /** 待捕获的类（内部名），由 retransform 触发回调时消费。 */
        private static final java.util.Set<String> PENDING_REDEFINE =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        /**
         * 在 {@code ClassFileLoadHook} 回调里被调用：此时 handle 仍属于 VM 的回调帧，
         * 提升为全局引用是合法的。
         */
        void onRedefine(String internalName, long jclass) {
            if (!PENDING_REDEFINE.remove(internalName)) {
                return;
            }
            try {
                MemorySegment global = newGlobalRef(MemorySegment.ofAddress(jclass));
                if (!global.equals(MemorySegment.NULL)) {
                    CLASS_REFS.put("L" + internalName + ";", global);
                }
            } catch (Throwable t) {
                Constants.LOG.debug("[JHook] capture of {} failed", internalName, t);
            }
        }

        /**
         * 只读探针：在 {@code jvmtiEnv} 内存里定位 {@code jvmtiCapabilities} 位域块。
         *
         * <p>思路：我们已经成功添加过 {@code can_get_constant_pool}（bit 35 → byte 4 的 0x08）
         * 和 {@code can_retransform_classes}（bit 37 → byte 4 的 0x20），所以该块的第 4 个字节
         * 应该是 {@code 0x28}。用它当特征扫描，命中位置减 4 就是块基址。</p>
         */
        void probeCapabilities() {
            try {
                int window = 512;
                MemorySegment env = MemorySegment.ofAddress(jvmtiEnv.address()).reinterpret(window);
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 64; i++) {
                    hex.append(String.format("%02x", env.get(ValueLayout.JAVA_BYTE, i) & 0xFF));
                    if ((i & 7) == 7) {
                        hex.append(' ');
                    }
                }
                Constants.LOG.info("[JHookProbe] jvmtiEnv=0x{} head={}",
                        Long.toHexString(jvmtiEnv.address()), hex);

                StringBuilder hits = new StringBuilder();
                int total = 0;
                for (int i = 0; i < window; i++) {
                    if ((env.get(ValueLayout.JAVA_BYTE, i) & 0xFF) == 0x28) {
                        total++;
                        if (hits.length() < 400) {
                            hits.append(i).append(' ');
                        }
                    }
                }
                Constants.LOG.info("[JHookProbe] 0x28 landmarks: {} hit(s), byte offsets [{}]", total, hits);
            } catch (Throwable t) {
                Constants.LOG.warn("[JHookProbe] capability scan failed", t);
            }
        }

        /** 取原始 jmethodID（未解引用），供 JVMTI 断点接口使用。 */
        long rawMethodId(Class<?> clazz, Method method) throws Throwable {
            MemorySegment jclass = findLoadedClass(clazz);
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            int index = isStatic ? JNI_GET_STATIC_METHOD_ID : JNI_GET_METHOD_ID;

            MethodHandle lookup = LINKER.downcallHandle(
                    jniTable.get(ValueLayout.ADDRESS, index * P),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MemorySegment id;
            try {
                id = (MemorySegment) lookup.invokeExact(jniEnv, jclass,
                        ARENA.allocateFrom(method.getName()),
                        ARENA.allocateFrom(descriptor(method)));
            } catch (Throwable failure) {
                // JNI GetMethodID 失败会把挂起异常留给 FFM 直接抛进 Java，这里必须清掉，
                // 否则同一线程后续的 JNI 调用都会失败。
                clearPendingException();
                StringBuilder names = new StringBuilder();
                for (Method m : clazz.getDeclaredMethods()) {
                    names.append(m.getName()).append(' ');
                }
                Constants.LOG.warn("[JHook] GetMethodID threw {} for {}.{}{} classHash={} jclassHash={} methods=[{}]",
                        failure.toString(), clazz.getName(), method.getName(), descriptor(method),
                        System.identityHashCode(clazz), classIdentityHash(jclass), names);
                throw failure;
            }
            if (id.equals(MemorySegment.NULL)) {
                clearPendingException();
                StringBuilder names = new StringBuilder();
                for (Method m : clazz.getDeclaredMethods()) {
                    names.append(m.getName()).append(' ');
                }
                Constants.LOG.warn("[JHook] GetMethodID failed: {}.{}{} classHash={} jclassHash={} methods=[{}]",
                        clazz.getName(), method.getName(), descriptor(method),
                        System.identityHashCode(clazz), classIdentityHash(jclass), names);
                throw new IllegalStateException("Method not found: " + clazz.getName() + "#" + method.getName());
            }
            return id.address();
        }

        /**
         * 强制丢弃这个方法的已编译代码，让后续调用重新走解释器/适配器，从而读到新的
         * {@code ConstMethod}。
         *
         * <p>用 JVMTI {@code SetBreakpoint} + {@code ClearBreakpoint}：断点会让该方法所在的
         * nmethod 变成 non-entrant、deopt 所有内联了它的调用方，但<b>不会重建方法元数据</b>
         * （这正是 {@code RetransformClasses} 做不到的——它重建元数据、把 hook 抹掉）。</p>
         */
        /**
         * 用 JVMTI 断点强制 deopt 的尝试。
         *
         * @deprecated 不可用、待移除。{@code can_generate_breakpoint_events} 属于 OnLoad 阶段的
         * 能力，live phase 的 {@code AddCapabilities} 会返回 {@code 98}
         * （JVMTI_ERROR_MUST_POSSESS_CAPABILITY），所以这条路径永远拿不到能力。
         * JIT 后的失效问题已由「先 retransform 再装 hook」的顺序解决，不再需要它。
         */
        @Deprecated(since = "0.1", forRemoval = true)
        boolean forceDeopt(Class<?> clazz, Method method) {
            try {
                if (!ensureBreakpointCapability()) {
                    return false;
                }
                MemorySegment mid = MemorySegment.ofAddress(rawMethodId(clazz, method));

                int setRc = (int) LINKER.downcallHandle(
                        jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_SET_BREAKPOINT * P),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
                        .invokeExact(jvmtiEnv, mid, 0L);
                int clearRc = (int) LINKER.downcallHandle(
                        jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_CLEAR_BREAKPOINT * P),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
                        .invokeExact(jvmtiEnv, mid, 0L);
                if (setRc != 0 || clearRc != 0) {
                    Constants.LOG.debug("[JHook] breakpoint-based deopt failed set={} clear={}", setRc, clearRc);
                    return false;
                }
                return true;
            } catch (Throwable t) {
                Constants.LOG.debug("[JHook] breakpoint-based deopt threw", t);
                return false;
            }
        }

        /** {@code can_generate_breakpoint_events}：jvmtiCapabilities bit 19，即 byte 0 的 0x00080000。 */
        private boolean ensureBreakpointCapability() {
            if (breakpointTried) {
                return breakpointReady;
            }
            breakpointTried = true;
            try {
                MemorySegment capabilities = ARENA.allocate(16);
                capabilities.fill((byte) 0);
                capabilities.set(ValueLayout.JAVA_INT, 0L, CAP_BREAKPOINT);
                MethodHandle add = LINKER.downcallHandle(
                        jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_ADD_CAPABILITIES * P),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                int rc = (int) add.invokeExact(jvmtiEnv, capabilities);
                breakpointReady = rc == 0;
                if (!breakpointReady) {
                    Constants.LOG.warn("[JHook] can_generate_breakpoint_events unavailable (rc={}); "
                            + "JIT-compiled methods will not be affected by hooks", rc);
                }
            } catch (Throwable t) {
                breakpointReady = false;
                Constants.LOG.warn("[JHook] failed to request breakpoint capability", t);
            }
            return breakpointReady;
        }

        /** 让 VM 自己按名字找一次，判断改写后的名字是否真的可解析。 */
        boolean canResolve(Class<?> clazz, Method method) {
            try {
                methodAddress(clazz, method);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * 让 JVM 丢弃这个类的已编译代码，并 deoptimize 所有依赖它的内联决策。
         *
         * <p>用 JVMTI {@code RetransformClasses}（函数表下标 151）实现：我们注册的
         * {@code ClassFileLoadHook} 不改字节码（没有转换器就直接返回），所以类内容不变；但 JVM 会把
         * 类里的方法标记为 old 并让依赖方失效，于是 <b>被 JIT 内联过的调用点会重新解析</b>，
         * 从而看到新装的 Hook。</p>
         *
         * <p>临时 Java Agent 的链路已删除：{@code Instrumentation.retransformClasses(Class...)}
         * 需要 {@code Class} 对象，本质上也是走 JVMTI，只是多了一次生成/加载 agent jar 的开销。
         * 这里直接用本类已经持有的 JVMTI 环境调用 {@code RetransformClasses}，jclass 来自
         * {@code Natives#findLoadedClass} 缓存的全局引用，跨原生调用不会失效。</p>
         *
         * <p>能力申请顺序：{@code can_retransform_classes} 必须在<b>同一个 JVMTI 环境</b>启用
         * {@code ClassFileLoadHook} 之前添加，否则 HotSpot 会返回
         * {@code JVMTI_ERROR_NOT_AVAILABLE}。本类的环境只做 retransform / 常量池查询，
         * {@code ClassFileLoadHook} 由 {@code FFMUtils} 在另一个环境里启用，所以这里能正常成功。</p>
         */
        boolean deoptimize(Class<?> clazz) {
            try {
                if (!ensureRetransformCapability()) {
                    return false;
                }
                String internalName = clazz.getName().replace('.', '/');
                MemorySegment jclass = findLoadedClass(clazz);
                MemorySegment classes = ARENA.allocate(P);
                // 让 retransform 期间触发的 ClassFileLoadHook 回调把这个类提升为全局引用。
                cn.ksmcbrigade.jhookmod.utils.ffm.FFMUtils.REDEFINE_SINK = this::onRedefine;
                PENDING_REDEFINE.add(internalName);
                classes.set(ValueLayout.ADDRESS, 0, jclass);

                MethodHandle retransform = LINKER.downcallHandle(
                        jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_RETRANSFORM_CLASSES * P),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                int rc = (int) retransform.invokeExact(jvmtiEnv, 1, classes);
                if (rc != 0) {
                    Constants.LOG.warn("[JHook] RetransformClasses({}) rc={}; "
                            + "already compiled call sites may ignore the hook", clazz.getSimpleName(), rc);
                    return false;
                }
                return true;
            } catch (Throwable t) {
                Constants.LOG.debug("[JHook] RetransformClasses failed", t);
                return false;
            }
        }

        /** {@code can_retransform_classes}：jvmtiCapabilities bit 37，即 byte 4 的 0x20。 */
        private boolean ensureRetransformCapability() {
            if (retransformTried) {
                return retransformReady;
            }
            retransformTried = true;
            try {
                MemorySegment capabilities = ARENA.allocate(16);
                capabilities.fill((byte) 0);
                capabilities.set(ValueLayout.JAVA_INT, 4L, CAP_CAN_RETRANSFORM_CLASSES);
                MethodHandle add = LINKER.downcallHandle(
                        jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_ADD_CAPABILITIES * P),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                int rc = (int) add.invokeExact(jvmtiEnv, capabilities);
                retransformReady = rc == 0;
                if (!retransformReady) {
                    Constants.LOG.warn("[JHook] can_retransform_classes unavailable (rc={}); "
                            + "call sites already inlined by the JIT may ignore the hook", rc);
                }
            } catch (Throwable t) {
                retransformReady = false;
                Constants.LOG.warn("[JHook] failed to request can_retransform_classes", t);
            }
            return retransformReady;
        }

        /** 常量池副本缓存：(源常量池地址 ^ 目标 Klass) -> 副本地址。 */
        private static final Map<Long, Long> CP_COPIES = new ConcurrentHashMap<>();
        private static final Arena CP_ARENA = Arena.ofShared();

        /**
         * 复制一份 hook 的常量池，把副本的 {@code pool_holder} 改成目标类，返回副本地址。
         *
         * <p>关键点：副本与原常量池<b>字节一致</b>（相对偏移不变），只有 {@code _pool_holder}
         * 被改写，因此 hook 类自己的常量池与其它方法完全不受影响。副本按
         * (源常量池, 目标类) 缓存，重复 Hook 不会反复分配。</p>
         *
         * @return 副本地址；失败返回 0
         */
        long copyConstantPool(long hookConst, long targetConst) {
            try {
                long constPoolOffset = offset("ConstMethod", "_constants");
                long poolHolderOffset = offset("ConstantPool", "_pool_holder");
                long lengthOffset = offset("ConstantPool", "_length");

                long sourceCp = readPtr(hookConst + constPoolOffset);
                long targetKlass = readPtr(readPtr(targetConst + constPoolOffset) + poolHolderOffset);
                long cacheKey = sourceCp ^ (targetKlass * 0x9E3779B97F4A7C15L);
                Long cached = CP_COPIES.get(cacheKey);
                if (cached != null) {
                    return cached;
                }

                int length = readInt(sourceCp + lengthOffset);
                if (length <= 0 || length > 200_000) {
                    return 0;
                }
                // 头部大小不硬编码：在条目区之前留足余量，整块按字节复制，相对偏移天然保持。
                long size = (long) length * 8 + 128;
                MemorySegment source = MemorySegment.ofAddress(sourceCp).reinterpret(size);
                MemorySegment copy = CP_ARENA.allocate(size, 8);
                MemorySegment.copy(source, 0, copy, 0, size);
                copy.set(ValueLayout.JAVA_LONG, poolHolderOffset, targetKlass);

                CP_COPIES.put(cacheKey, copy.address());
                return copy.address();
            } catch (Throwable t) {
                Constants.LOG.debug("[JHook] failed to copy the constant pool", t);
                return 0;
            }
        }

        // ---------- 常量池 ----------

        /**
         * 解析出「能在这份 {@code ConstMethod} 所属常量池里表示 {@code wantedName}」的索引（只读）。
         *
         * <p>{@code _name_index} 是 {@code ConstMethod} 所属常量池里的下标，不能拿别的类的索引来用。
         * 分两种情况：</p>
         * <ul>
         *   <li>两个 {@code ConstMethod} 同属一个常量池（同一个类）→ 直接借用
         *       {@code templateConstMethod} 的名字索引，零依赖；</li>
         *   <li>跨类 → 需要在 {@code poolOwner} 的常量池里找同名 UTF8，
         *       这要求 {@code can_get_constant_pool} 能力可用。</li>
         * </ul>
         *
         * @return 可用的名字索引；无法解析时返回 {@code -1}
         */
        int resolveNameIndex(long nameOffset, long templateConstMethod, boolean sameConstantPool,
                             Class<?> poolOwner, String wantedName) throws Throwable {
            if (sameConstantPool) {
                return readU2(templateConstMethod + nameOffset);
            }
            return utf8Index(poolOwner, wantedName);
        }

        /** {@code GetConstantPool} 需要 {@code can_get_constant_pool}（jvmtiCapabilities bit 35）。 */
        private boolean ensureConstantPoolCapability() {
            if (capabilityTried) {
                return capabilityReady;
            }
            capabilityTried = true;
            try {
                MemorySegment capabilities = ARENA.allocate(16);
                capabilities.fill((byte) 0);
                capabilities.set(ValueLayout.JAVA_INT, 4L, CAP_CAN_GET_CONSTANT_POOL);
                MethodHandle add = LINKER.downcallHandle(
                        jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_ADD_CAPABILITIES * P),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                int rc = (int) add.invokeExact(jvmtiEnv, capabilities);
                capabilityReady = rc == 0;
                if (!capabilityReady) {
                    Constants.LOG.warn("[JHook] can_get_constant_pool unavailable (rc={}); "
                            + "cannot preserve the original name when hooking across classes", rc);
                }
            } catch (Throwable t) {
                capabilityReady = false;
                Constants.LOG.warn("[JHook] failed to request can_get_constant_pool", t);
            }
            return capabilityReady;
        }

        /**
         * 在 {@code clazz} 的常量池里找值为 {@code text} 的 UTF8 项，返回其下标；找不到返回 {@code -1}。
         * <p>用 JVMTI {@code GetConstantPool} 拿原始常量池字节后按 class 文件格式遍历。</p>
         */
        int utf8Index(Class<?> clazz, String text) throws Throwable {
            if (!ensureConstantPoolCapability()) {
                return -1;
            }
            MemorySegment jclass = findLoadedClass(clazz);
            MethodHandle getPool = LINKER.downcallHandle(
                    jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_GET_CONSTANT_POOL * P),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            // 注意签名是 5 个参数：env, klass, &constant_pool_count, &byte_count, &bytes
            MemorySegment entryCountSeg = ARENA.allocate(ValueLayout.JAVA_INT);
            MemorySegment countSeg = ARENA.allocate(ValueLayout.JAVA_INT);
            MemorySegment bytesSeg = ARENA.allocate(ValueLayout.ADDRESS);
            int rc = (int) getPool.invokeExact(jvmtiEnv, jclass, entryCountSeg, countSeg, bytesSeg);
            if (rc != 0) {
                Constants.LOG.warn("[JHook] GetConstantPool({}) failed rc={}", clazz.getSimpleName(), rc);
                return -1;
            }
            int byteCount = countSeg.get(ValueLayout.JAVA_INT, 0);
            MemorySegment pool = bytesSeg.get(ValueLayout.ADDRESS, 0);
            if (pool.equals(MemorySegment.NULL) || byteCount < 2 || byteCount > 4 * 1024 * 1024) {
                Constants.LOG.warn("[JHook] GetConstantPool({}) returned unusable data byteCount={}",
                        clazz.getSimpleName(), byteCount);
                return -1;
            }
            MemorySegment cp = pool.reinterpret(byteCount);
            try {
                // 返回的字节只有常量池项本体，不含 u2 的 constant_pool_count 前缀，
                // 条目数从第一个出参单独取。
                int cpCount = entryCountSeg.get(ValueLayout.JAVA_INT, 0);
                int index = scanUtf8(cp, byteCount, cpCount, text);
                Constants.LOG.debug("[JHook] GetConstantPool({}) bytes={} entries={} -> {}={}",
                        clazz.getSimpleName(), byteCount, cpCount, text, index);
                return index;
            } finally {
                deallocate(pool);
            }
        }

        private int scanUtf8(MemorySegment cp, int byteCount, int cpCount, String text) {
            int pos = 0;
            for (int i = 1; i < cpCount; i++) {
                if (pos >= byteCount) {
                    return -1;
                }
                int tag = cp.get(ValueLayout.JAVA_BYTE, pos++) & 0xFF;
                switch (tag) {
                    case 1 -> { // Utf8
                        int length = u2(cp, pos);
                        pos += 2;
                        if (pos + length > byteCount) {
                            return -1;
                        }
                        if (matches(cp, pos, length, text)) {
                            return i;
                        }
                        pos += length;
                    }
                    case 3, 4 -> pos += 4;            // Integer / Float
                    case 5, 6 -> {                    // Long / Double 占两个下标
                        pos += 8;
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> pos += 2; // Class / String / MethodType / Module / Package
                    case 9, 10, 11, 12, 17, 18 -> pos += 4; // Field/Method/InterfaceMethod/NameAndType/...
                    case 15 -> pos += 3;              // MethodHandle
                    default -> {
                        return -1;
                    }
                }
            }
            return -1;
        }

        private boolean matches(MemorySegment cp, int pos, int length, String text) {
            if (length != text.length()) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                int b = cp.get(ValueLayout.JAVA_BYTE, pos + i) & 0xFF;
                if (b != (text.charAt(i) & 0xFF)) {
                    return false;
                }
            }
            return true;
        }

        private int u2(MemorySegment cp, int pos) {
            return ((cp.get(ValueLayout.JAVA_BYTE, pos) & 0xFF) << 8)
                    | (cp.get(ValueLayout.JAVA_BYTE, pos + 1) & 0xFF);
        }

        private void deallocate(MemorySegment memory) {
            try {
                MethodHandle deallocate = LINKER.downcallHandle(
                        jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_DEALLOCATE * P),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                deallocate.invokeExact(jvmtiEnv, memory);
            } catch (Throwable ignored) {
                // 释放失败不影响主流程
            }
        }

        // ---------- 类查找 ----------

        /**
         * 已解析的 <b>全局</b> jclass 引用，按类签名缓存。
         *
         * <p>为什么必须是全局引用：{@code GetLoadedClasses} 返回的每个 jclass 都是**当前原生调用**
         * 的 JNI 局部引用，而 FFM 的每次 {@code invokeExact} 都是一次独立的原生入口，
         * 返回时局部帧就没了。把这个局部引用存下来下次再用，就是踩悬空 handle
         * （26.2 上表现为 {@code jvmti_GetClassSignature} 里 {@code oopDesc::is_a} 崩溃）。
         * {@code NewGlobalRef} 出来的引用由 VM 永久持有，跨调用始终有效。</p>
         */
        private static final Map<String, MemorySegment> CLASS_REFS = new ConcurrentHashMap<>();

        /** 按 {@link Class} 对象缓存 jclass：同名类可能有多个 classloader 副本。 */
        private static final Map<Class<?>, MemorySegment> CLASS_REFS_BY_CLASS = new ConcurrentHashMap<>();

        /** {@code java.lang.Object.hashCode()I}，用于比较 {@code Class} 对象的身份哈希。 */
        private static volatile MemorySegment objectHashCodeMethodId;

        private MemorySegment findLoadedClass(String wantedSignature) throws Throwable {
            MemorySegment cached = CLASS_REFS.get(wantedSignature);
            if (cached != null) {
                return cached;
            }
            MemorySegment global = scanLoadedClass(wantedSignature, false, 0);
            if (global == null) {
                throw new IllegalStateException("Loaded class not found: " + wantedSignature);
            }
            CLASS_REFS.put(wantedSignature, global);
            return global;
        }

        /**
         * 按 {@link Class} 对象取 jclass，而不是只按类名。
         *
         * <p>同名类可能被不同 classloader 各加载一份（Fabric/NeoForge 的 transform/remap 下实测存在），
         * 只按签名扫描会随机拿到另一份，随后 {@code GetMethodID} 报
         * {@code NoSuchMethodError}。这里用 {@code System.identityHashCode(clazz)} 与候选
         * {@code Class} 对象的身份哈希比对，确保拿到的是传入的那个类。</p>
         */
        private MemorySegment findLoadedClass(Class<?> clazz) throws Throwable {
            MemorySegment cached = CLASS_REFS_BY_CLASS.get(clazz);
            if (cached != null) {
                return cached;
            }
            String wantedSignature = "L" + clazz.getName().replace('.', '/') + ";";
            MemorySegment global = scanLoadedClass(wantedSignature, true, System.identityHashCode(clazz));
            if (global == null) {
                throw new IllegalStateException("Loaded class not found: " + wantedSignature);
            }
            CLASS_REFS_BY_CLASS.put(clazz, global);
            return global;
        }

        /**
         * 扫描所有已加载类；{@code checkIdentity} 时只返回身份哈希匹配的那个（找不到再退回第一个同名类）。
         */
        private MemorySegment scanLoadedClass(String wantedSignature, boolean checkIdentity, int wantedHash)
                throws Throwable {
            // 只用 JVMTI GetLoadedClasses 扫描来取句柄。
            // 不能用 JNI FindClass：它返回的 jclass 是那次 downcall 的局部引用，跨下行调用后
            // 已失效/被复用（实测 java.lang.System 会被解析成 [B），随后 GetMethodID/
            // GetStaticMethodID 直接让 JVM 崩溃。
            MethodHandle getLoaded = LINKER.downcallHandle(
                    jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_GET_LOADED_CLASSES * P),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MemorySegment countSeg = ARENA.allocate(ValueLayout.JAVA_INT);
            MemorySegment arraySeg = ARENA.allocate(ValueLayout.ADDRESS);
            if ((int) getLoaded.invokeExact(jvmtiEnv, countSeg, arraySeg) != 0) {
                throw new IllegalStateException("GetLoadedClasses failed");
            }
            int count = countSeg.get(ValueLayout.JAVA_INT, 0);
            MemorySegment classes = arraySeg.get(ValueLayout.ADDRESS, 0).reinterpret(count * P);

            MethodHandle getSig = LINKER.downcallHandle(
                    jvmtiTable.get(ValueLayout.ADDRESS, JVMTI_GET_CLASS_SIGNATURE * P),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MemorySegment sigSeg = ARENA.allocate(ValueLayout.ADDRESS);
            MemorySegment genSeg = ARENA.allocate(ValueLayout.ADDRESS);

            MemorySegment fallback = null;
            for (int i = 0; i < count; i++) {
                MemorySegment klass = classes.get(ValueLayout.ADDRESS, i * P);
                sigSeg.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                genSeg.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                if ((int) getSig.invokeExact(jvmtiEnv, klass, sigSeg, genSeg) != 0) {
                    continue;
                }
                MemorySegment sig = sigSeg.get(ValueLayout.ADDRESS, 0);
                if (sig.equals(MemorySegment.NULL) || !cstr(sig).equals(wantedSignature)) {
                    continue;
                }
                // 立刻提升为全局引用：局部引用在本次下行调用返回后就失效了
                MemorySegment global = newGlobalRef(klass);
                if (global.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("NewGlobalRef failed for " + wantedSignature);
                }
                if (!checkIdentity) {
                    return global;
                }
                int hash = -1;
                try {
                    hash = classIdentityHash(global);
                } catch (Throwable t) {
                    Constants.LOG.debug("[JHook] failed to read the identity hash of {}", wantedSignature, t);
                }
                if (hash == wantedHash) {
                    return global;
                }
                if (fallback == null) {
                    fallback = global;
                }
            }
            return fallback;
        }

        /** 用 JNI 调用 {@code Object.hashCode()}，对 {@code Class} 对象就是身份哈希。 */
        private int classIdentityHash(MemorySegment classRef) throws Throwable {
            MemorySegment methodId = objectHashCodeMethodId;
            if (methodId == null) {
                MemorySegment objectClass = findLoadedClass("Ljava/lang/Object;");
                MethodHandle getMethod = LINKER.downcallHandle(
                        jniTable.get(ValueLayout.ADDRESS, JNI_GET_METHOD_ID * P),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MemorySegment found = (MemorySegment) getMethod.invokeExact(jniEnv, objectClass,
                        (MemorySegment) ARENA.allocateFrom("hashCode"),
                        (MemorySegment) ARENA.allocateFrom("()I"));
                if (found.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("Object.hashCode() not found");
                }
                objectHashCodeMethodId = found;
                methodId = found;
            }
            MemorySegment args = ARENA.allocate(8);
            args.fill((byte) 0);
            MethodHandle call = LINKER.downcallHandle(
                    jniTable.get(ValueLayout.ADDRESS, JNI_CALL_INT_METHOD_A * P),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            return (int) call.invokeExact(jniEnv, classRef, methodId, args);
        }

        /** {@code ExceptionClear}：JNI 函数表索引 17，清理 JNI 失败留下的挂起异常。 */
        private void clearPendingException() {
            try {
                MethodHandle clear = LINKER.downcallHandle(
                        jniTable.get(ValueLayout.ADDRESS, 17 * P),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                clear.invokeExact(jniEnv);
            } catch (Throwable ignored) {
                // 尽力而为
            }
        }

        /** {@code NewGlobalRef}：JNI 函数表索引 21，返回跨调用永久有效的引用。 */
        private MemorySegment newGlobalRef(MemorySegment localRef) throws Throwable {            MethodHandle handle = LINKER.downcallHandle(
                    jniTable.get(ValueLayout.ADDRESS, 21 * P),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            return (MemorySegment) handle.invokeExact(jniEnv, localRef);
        }

        // ---------- 引导 ----------

        private MemorySegment attachCurrentThread() throws Throwable {
            for (int index : new int[]{7, 4}) {
                MemorySegment fn = invokeTable.get(ValueLayout.ADDRESS, index * P);
                if (fn.equals(MemorySegment.NULL)) {
                    continue;
                }
                MethodHandle attach = LINKER.downcallHandle(fn, FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MemorySegment out = ARENA.allocate(ValueLayout.ADDRESS);
                if ((int) attach.invokeExact(javaVM, out, MemorySegment.NULL) == 0) {
                    return out.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
                }
            }
            throw new IllegalStateException("AttachCurrentThread failed");
        }

        private MemorySegment getJvmtiEnv() throws Throwable {
            MethodHandle getEnv = LINKER.downcallHandle(
                    invokeTable.get(ValueLayout.ADDRESS, 6 * P),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            int current = 0x30000000 | (Runtime.version().feature() << 16);
            for (int version : new int[]{current, 0x30010200, 0x30010000}) {
                MemorySegment out = ARENA.allocate(ValueLayout.ADDRESS);
                out.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                if ((int) getEnv.invokeExact(javaVM, out, version) == 0) {
                    MemorySegment env = out.get(ValueLayout.ADDRESS, 0);
                    if (!env.equals(MemorySegment.NULL)) {
                        return env.reinterpret(Long.MAX_VALUE);
                    }
                }
            }
            throw new IllegalStateException("GetEnv(JVMTI) failed");
        }

        // ---------- 内存读写 ----------

        long readPtr(long addr) {
            return MemorySegment.ofAddress(addr).reinterpret(8).get(ValueLayout.JAVA_LONG, 0);
        }

        void writePtr(long addr, long value) {
            MemorySegment.ofAddress(addr).reinterpret(8).set(ValueLayout.JAVA_LONG, 0, value);
        }

        int readU2(long addr) {
            return MemorySegment.ofAddress(addr).reinterpret(2)
                    .get(ValueLayout.JAVA_SHORT, 0) & 0xFFFF;
        }

        void writeU2(long addr, int value) {
            MemorySegment.ofAddress(addr).reinterpret(2)
                    .set(ValueLayout.JAVA_SHORT, 0, (short) value);
        }

        int readInt(long addr) {
            return MemorySegment.ofAddress(addr).reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        }

        void writeInt(long addr, int value) {
            MemorySegment.ofAddress(addr).reinterpret(4).set(ValueLayout.JAVA_INT, 0, value);
        }

        String describe(Method method) {
            return descriptor(method);
        }

        // ---------- 符号 ----------

        private MemorySegment moduleHandle(String name) throws Throwable {
            MethodHandle getModuleHandleW = LINKER.downcallHandle(
                    kernel32().find("GetModuleHandleW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            try (Arena local = Arena.ofConfined()) {
                MemorySegment handle = (MemorySegment) getModuleHandleW.invokeExact(toWideString(local, name));
                if (handle.equals(MemorySegment.NULL)) {
                    throw new UnsatisfiedLinkError(name + " is not loaded");
                }
                return handle;
            }
        }

        private MemorySegment symbol(String name) {
            try {
                MethodHandle getProcAddress = LINKER.downcallHandle(
                        kernel32().find("GetProcAddress").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MemorySegment p = (MemorySegment) getProcAddress.invokeExact(jvmModule, ARENA.allocateFrom(name));
                if (p.equals(MemorySegment.NULL)) {
                    throw new UnsatisfiedLinkError("symbol not found: " + name);
                }
                return p.reinterpret(64);
            } catch (Throwable t) {
                throw new RuntimeException("cannot resolve symbol " + name, t);
            }
        }

        private long readU64Symbol(String name) {
            return symbol(name).get(ValueLayout.JAVA_LONG, 0);
        }

        private static SymbolLookup kernel32() {
            return SymbolLookup.libraryLookup("kernel32", ARENA)
                    .or(SymbolLookup.loaderLookup())
                    .or(LINKER.defaultLookup());
        }
    }

    private static String cstr(MemorySegment p) {
        MemorySegment s = p.reinterpret(256);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            byte b = s.get(ValueLayout.JAVA_BYTE, i);
            if (b == 0) {
                break;
            }
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }

    private static MemorySegment toWideString(Arena arena, String s) {
        MemorySegment seg = arena.allocate(ValueLayout.JAVA_CHAR, s.length() + 1);
        for (int i = 0; i < s.length(); i++) {
            seg.setAtIndex(ValueLayout.JAVA_CHAR, i, s.charAt(i));
        }
        seg.setAtIndex(ValueLayout.JAVA_CHAR, s.length(), (char) 0);
        return seg;
    }

    public static void tryInstallHook(Class<?> targetClazz, String targetName, Class<?>[] targetParams,
                                    Class<?> hookClazz,String hookName, Class<?>[] hookParams, boolean nonPublic) {
        try {
            Method original = targetClazz.getDeclaredMethod(targetName, targetParams);
            Method hook = hookClazz.getDeclaredMethod(hookName, hookParams);
            JHookUtils.probeMethodLayout(targetClazz, original);
            if (nonPublic) {
                JHookUtils.hookNonPublic(targetClazz, original,hookClazz, hook);
            } else {
                JHookUtils.hookPublic(targetClazz, original, hookClazz, hook);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("[JHook] failed to install the hook on "+
                    targetClazz.getSimpleName()+" : "+targetName, t);
        }
    }
}
