package cn.ksmcbrigade.jhookmod.utils.hook;

import cn.ksmcbrigade.jhookmod.Constants;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Repeatable self-test for {@link JHookUtils}.
 *
 * <p>Covers every shape we care about: visibility (public / private / package-private /
 * protected, same class and cross class), primitive / void / array signatures, overloads,
 * JIT-compiled targets, backup (around) hooks, repeated hook/unhook and the retransform
 * de-duplication.</p>
 *
 * <p>Every {@link Method} is resolved up front because {@code retransformClasses} invalidates
 * the reflection cache: looking a method up by name after a hook may fail.</p>
 */
public final class JHookUtilsSelfTest {

    private JHookUtilsSelfTest() {
    }

    private static int passed;
    private static int failed;
    private static final Map<String, Method> METHODS = new HashMap<>();

    // ------------------------------------------------------------------
    //  Test fixtures
    // ------------------------------------------------------------------

    /** Target methods of every visibility and shape. */
    public static class Target {
        public String publicMethod() { return "public"; }

        private String privateMethod() { return "private"; }

        String packageMethod() { return "package"; }

        protected String protectedMethod() { return "protected"; }

        public String callPrivate() { return privateMethod(); }

        public String callPackage() { return packageMethod(); }

        public String callProtected() { return protectedMethod(); }

        public int add(int a, int b) { return a + b; }

        public void noReturn(String s) { }

        public int[] arrayArg(int[] a) { return a; }

        public String over(int a) { return "int:" + a; }

        public String over(String a) { return "str:" + a; }

        public int hot(int i) { return i * 2; }

        private String sameClassTarget() { return "original"; }

        public String callSameClassTarget() { return sameClassTarget(); }

        private String sameClassHook() { return "HOOKED-same-class"; }
    }

    /** Hook bodies. Only constants / public members: {@code hookNonPublic} re-homes the pool. */
    public static class Hook {
        public static String lastCall;

        public String publicMethod() { return "HOOK-public"; }

        public String privateMethod() { return "HOOK-private"; }

        public String packageMethod() { return "HOOK-package"; }

        public String protectedMethod() { return "HOOK-protected"; }

        public int add(int a, int b) { return -1000 + a + b; }

        public void noReturn(String s) { lastCall = "HOOK-void:" + s; }

        public int[] arrayArg(int[] a) { return new int[]{9, 9}; }

        public String over(int a) { return "HOOK-int:" + a; }

        public String over(String a) { return "HOOK-str:" + a; }

        public int hot(int i) { return -i; }
    }

    /** Target plus a spare method that receives the original body (around hook). */
    public static class BackupTarget {
        public String foo(String s) { return "orig:" + s; }

        public String around(String s) { return "wrap(" + original(s) + ")"; }

        public String original(String s) { throw new IllegalStateException("placeholder"); }
    }

    /** Cold call site, used to observe a method that has already been JIT compiled. */
    public static class Probe {
        public static int callHot(Target t, int i) { return t.hot(i); }
    }

    // ------------------------------------------------------------------
    //  Entry point
    // ------------------------------------------------------------------

    /** Runs the whole matrix. Returns {@code true} when everything passed. */
    public static boolean run() {
        passed = 0;
        failed = 0;
        prime();

        Target target = new Target();

        section("public, cross class");
        JHookUtils.hookPublic(Target.class, m(Target.class, "publicMethod"),
                Hook.class, m(Hook.class, "publicMethod"));
        check("public hooked", target.publicMethod(), "HOOK-public");
        JHookUtils.unhook(Target.class, m(Target.class, "publicMethod"));
        check("public restored", target.publicMethod(), "public");

        section("non-public, cross class (hookNonPublic)");
        JHookUtils.hookNonPublic(Target.class, m(Target.class, "privateMethod"),
                Hook.class, m(Hook.class, "privateMethod"));
        check("private hooked", target.callPrivate(), "HOOK-private");
        JHookUtils.unhook(Target.class, m(Target.class, "privateMethod"));
        check("private restored", target.callPrivate(), "private");

        JHookUtils.hookNonPublic(Target.class, m(Target.class, "packageMethod"),
                Hook.class, m(Hook.class, "packageMethod"));
        check("package hooked", target.callPackage(), "HOOK-package");
        JHookUtils.unhook(Target.class, m(Target.class, "packageMethod"));

        JHookUtils.hookNonPublic(Target.class, m(Target.class, "protectedMethod"),
                Hook.class, m(Hook.class, "protectedMethod"));
        check("protected hooked", target.callProtected(), "HOOK-protected");
        JHookUtils.unhook(Target.class, m(Target.class, "protectedMethod"));

        section("non-public, cross class rejected by hookPublic");
        try {
            JHookUtils.hookPublic(Target.class, m(Target.class, "privateMethod"),
                    Hook.class, m(Hook.class, "privateMethod"));
            check("rejected", "accepted", "IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            check("rejected", e.getClass().getSimpleName(), "IllegalArgumentException");
        }

        section("non-public, same class");
        JHookUtils.hookPublic(Target.class, m(Target.class, "sameClassTarget"),
                Target.class, m(Target.class, "sameClassHook"));
        check("same class hooked", target.callSameClassTarget(), "HOOKED-same-class");
        JHookUtils.unhook(Target.class, m(Target.class, "sameClassTarget"));
        check("same class restored", target.callSameClassTarget(), "original");

        section("signatures");
        JHookUtils.hookPublic(Target.class, m(Target.class, "add", int.class, int.class),
                Hook.class, m(Hook.class, "add", int.class, int.class));
        check("int add", target.add(1, 2), -997);
        JHookUtils.unhook(Target.class, m(Target.class, "add", int.class, int.class));

        Hook.lastCall = null;
        JHookUtils.hookPublic(Target.class, m(Target.class, "noReturn", String.class),
                Hook.class, m(Hook.class, "noReturn", String.class));
        target.noReturn("x");
        check("void", Hook.lastCall, "HOOK-void:x");
        JHookUtils.unhook(Target.class, m(Target.class, "noReturn", String.class));

        JHookUtils.hookPublic(Target.class, m(Target.class, "arrayArg", int[].class),
                Hook.class, m(Hook.class, "arrayArg", int[].class));
        check("array", target.arrayArg(new int[]{1}), new int[]{9, 9});
        JHookUtils.unhook(Target.class, m(Target.class, "arrayArg", int[].class));

        section("overloads");
        JHookUtils.hookPublic(Target.class, m(Target.class, "over", int.class),
                Hook.class, m(Hook.class, "over", int.class));
        check("over(int) hooked", target.over(7), "HOOK-int:7");
        check("over(String) untouched", target.over("x"), "str:x");
        JHookUtils.unhook(Target.class, m(Target.class, "over", int.class));

        section("around hook (backup)");
        BackupTarget around = new BackupTarget();
        try {
            JHookUtils.hookWithBackup(BackupTarget.class, m(BackupTarget.class, "foo", String.class),
                    BackupTarget.class, m(BackupTarget.class, "around", String.class),
                    BackupTarget.class, m(BackupTarget.class, "original", String.class));
            check("around hook", around.foo("x"), "wrap(orig:x)");
        } catch (IllegalStateException e) {
            check("rolled back cleanly", around.foo("x"), "orig:x");
        }

        section("two hooks on the same class (retransform de-duplication)");
        JHookUtils.hookPublic(Target.class, m(Target.class, "over", int.class),
                Hook.class, m(Hook.class, "over", int.class));
        JHookUtils.hookPublic(Target.class, m(Target.class, "add", int.class, int.class),
                Hook.class, m(Hook.class, "add", int.class, int.class));
        check("first still active", target.over(7), "HOOK-int:7");
        check("second active", target.add(1, 2), -997);
        JHookUtils.unhook(Target.class, m(Target.class, "over", int.class));
        JHookUtils.unhook(Target.class, m(Target.class, "add", int.class, int.class));

        section("already JIT compiled");
        warm(target);
        JHookUtils.hookPublic(Target.class, m(Target.class, "hot", int.class),
                Hook.class, m(Hook.class, "hot", int.class));
        check("hooked after warm-up", Probe.callHot(target, 5), -5);
        warm(target);
        check("still hooked after recompilation", Probe.callHot(target, 5), -5);
        JHookUtils.unhook(Target.class, m(Target.class, "hot", int.class));
        check("restored", Probe.callHot(target, 5), 10);

        section("repeated hook/unhook");
        for (int i = 0; i < 3; i++) {
            JHookUtils.hookPublic(Target.class, m(Target.class, "add", int.class, int.class),
                    Hook.class, m(Hook.class, "add", int.class, int.class));
            boolean hooked = target.add(1, 2) == -997;
            JHookUtils.unhook(Target.class, m(Target.class, "add", int.class, int.class));
            check("round " + (i + 1), hooked + "/" + (target.add(1, 2) == 3), "true/true");
        }

        Constants.LOG.info("[JHookSelfTest] passed={} failed={}", passed, failed);
        return failed == 0;
    }

    /** Convenience entry point for a dev run. */
    public static void runOrThrow() {
        if (!run()) {
            throw new IllegalStateException(failed + " JHookUtils self-test case(s) failed");
        }
    }

    private static void warm(Target t) {
        long sum = 0;
        for (int i = 0; i < 800000; i++) {
            sum += t.hot(i);
        }
    }

    private static void prime() {
        for (Class<?> c : new Class<?>[]{Target.class, Hook.class, BackupTarget.class}) {
            for (Method mm : c.getDeclaredMethods()) {
                METHODS.put(key(c, mm.getName(), mm.getParameterTypes()), mm);
            }
        }
    }

    private static Method m(Class<?> c, String name, Class<?>... params) {
        Method cached = METHODS.get(key(c, name, params));
        if (cached != null) {
            return cached;
        }
        try {
            Method resolved = c.getDeclaredMethod(name, params);
            METHODS.put(key(c, name, params), resolved);
            return resolved;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(Class<?> c, String name, Class<?>[] params) {
        return c.getName() + "#" + name + Arrays.toString(params);
    }

    private static void section(String title) {
        Constants.LOG.info("[JHookSelfTest] -- {}", title);
    }

    private static void check(String name, Object actual, Object expected) {
        boolean ok = (actual instanceof int[] a && expected instanceof int[] b)
                ? Arrays.equals(a, b)
                : java.util.Objects.equals(actual, expected);
        if (ok) {
            passed++;
            Constants.LOG.info("[JHookSelfTest] PASS {} (actual={})", name, render(actual));
        } else {
            failed++;
            Constants.LOG.error("[JHookSelfTest] FAIL {} (actual={}, expected={})",
                    name, render(actual), render(expected));
        }
    }

    private static String render(Object value) {
        return value instanceof int[] a ? Arrays.toString(a) : String.valueOf(value);
    }
}