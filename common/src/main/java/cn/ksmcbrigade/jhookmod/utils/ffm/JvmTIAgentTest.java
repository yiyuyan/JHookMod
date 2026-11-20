package cn.ksmcbrigade.jhookmod.utils.ffm;

import cn.ksmcbrigade.jhookmod.Constants;
import cn.ksmcbrigade.jhookmod.utils.hook.JHookUtils;

import java.lang.reflect.Method;

/**
 * Demo / smoke test for {@link JHookUtils}: replaces {@code foo} with {@code hooked_foo} and moves
 * the original body onto {@code original_foo}, forming an around hook.
 *
 * <p>All three methods are <b>instance</b> methods with identical descriptors, so the receiver
 * ({@code this}) and arguments are passed through untouched.</p>
 */
public class JvmTIAgentTest {

    /** Instance state used to prove the hook receives the very same receiver object. */
    private final String instanceTag;

    /** Receiver observed inside {@code hooked_foo}, for the assertion below. */
    private static volatile int lastHookReceiver;
    private static volatile String lastHookTag;

    public JvmTIAgentTest() {
        this("default");
    }

    public JvmTIAgentTest(String instanceTag) {
        this.instanceTag = instanceTag;
    }

    /** Installs the hook via {@link JHookUtils} and verifies it. */
    public static void hookFooAndTest() {
        try {
            Method foo = JvmTIAgentTest.class.getDeclaredMethod("foo", String.class);
            Method hooked = JvmTIAgentTest.class.getDeclaredMethod("hooked_foo", String.class);
            Method original = JvmTIAgentTest.class.getDeclaredMethod("original_foo", String.class);

            // foo -> hooked_foo's bytecode; foo's original body -> original_foo
            JHookUtils.hookWithBackup(JvmTIAgentTest.class, foo,
                    JvmTIAgentTest.class, hooked,
                    JvmTIAgentTest.class, original);

            // No warm-up: the first call happens after the hook is installed.
            JvmTIAgentTest target = new JvmTIAgentTest("instance-A");
            int targetIdentity = System.identityHashCode(target);
            Constants.LOG.info("[JHookDemo] caller receiver this=0x{} tag={}",
                    Integer.toHexString(targetIdentity), target.instanceTag);

            int result = target.foo("testJHook");
            boolean sameInstance = lastHookReceiver == targetIdentity;
            Constants.LOG.info("[JHookDemo] hooked_foo saw this=0x{} tag={} -> {}",
                    Integer.toHexString(lastHookReceiver), lastHookTag,
                    sameInstance ? "same instance" : "DIFFERENT instance!");
            Constants.LOG.info("[JHookDemo] foo(\"testJHook\") returned {}", result);

            // Reflection goes through a fresh name lookup and must pass the receiver too.
            Method reflected = JvmTIAgentTest.class.getDeclaredMethod("foo", String.class);
            Object reflectedResult = reflected.invoke(target, "reflective");
            Constants.LOG.info("[JHookDemo] reflected foo returned {}", reflectedResult);

            boolean pass = result == 2 && sameInstance && Integer.valueOf(2).equals(reflectedResult);
            Constants.LOG.info("[JHookDemo] {}", pass
                    ? "PASS: foo runs hooked_foo, the receiver is the same instance, "
                      + "and hooked_foo reached the original body through original_foo"
                    : "FAIL: result=" + result + ", sameInstance=" + sameInstance
                      + ", reflected=" + reflectedResult);
        } catch (Throwable t) {
            Constants.LOG.error("[JHookDemo] demo failed", t);
        }
    }

    /** Target method: calling it actually runs {@link #hooked_foo}'s bytecode. */
    public int foo(String arg) {
        Constants.LOG.info("[JHookDemo] original foo: {}! (tag={})", arg, instanceTag);
        return 1;
    }

    /**
     * Replacement body. Same descriptor as {@code foo}, so receiver and arguments are passed
     * through unchanged; it then calls {@link #original_foo} to run the original logic.
     */
    public int hooked_foo(String arg) {
        lastHookReceiver = System.identityHashCode(this);
        lastHookTag = this.instanceTag;

        Constants.LOG.info("[JHookDemo] hooked_foo called with arg: {}", arg);

        int original = original_foo(arg);
        Constants.LOG.info("[JHookDemo] original foo returned {}", original);
        return original + 1;
    }

    /**
     * Placeholder: at runtime its {@code ConstMethod} is replaced by foo's original body,
     * so this body is never executed.
     */
    public int original_foo(String arg) {
        throw new IllegalStateException("original_foo placeholder body must never run");
    }
}
