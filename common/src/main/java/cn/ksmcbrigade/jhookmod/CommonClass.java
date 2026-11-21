package cn.ksmcbrigade.jhookmod;

import cn.ksmcbrigade.jhookmod.platform.Services;
import cn.ksmcbrigade.jhookmod.utils.ffm.FFMUtils;
import cn.ksmcbrigade.jhookmod.utils.ffm.JvmTIAgentTest;
import cn.ksmcbrigade.jhookmod.utils.hook.JHookUtils;
import cn.ksmcbrigade.jhookmod.utils.hook.JHookUtilsSelfTest;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

public class CommonClass {

    public static void init() {
        Constants.LOG.info("Hello {} Platform: {} Environment: {}!", Constants.MOD_NAME,
                Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());
        Constants.LOG.info("JVMTI ClassFileLoadHook: injected={}, invocations so far={}",
                FFMUtils.isInjected(), FFMUtils.getHookInvocations());

        if (Services.PLATFORM.isDevelopmentEnvironment() || Boolean.getBoolean("jhook.selftest")) {
            JvmTIAgentTest.hookFooAndTest();

            // Repeatable API self-test (visibility matrix, signatures, overloads, JIT, backup...).
            JHookUtilsSelfTest.runOrThrow();

            int crossClass = new JvmTIAgentTest("cross-class").foo("cross-class");
            Constants.LOG.info("[JHook] fresh cross-class call site foo() returned {} -> {}",
                    crossClass, crossClass == 2 ? "PASS" : "FAIL");


        }

        // 例 1：System.exit 改成强制 halt(0)。目标 public static，hook 也必须 static。
        installHook(System.class, "exit", new Class<?>[]{int.class},
                "exitForceZero", new Class<?>[]{int.class}, false);

        // 例 2：窗口标题。updateTitle 是 public，普通 hook 即可稳定工作。
        // installHook(Minecraft.class, "updateTitle", new Class<?>[0],
        //         "createTitleHookTest", new Class<?>[0], false);

        // 实验：createTitle 是 private 实例方法，走 hookNonPublic（常量池副本方案）。
        if(!Services.PLATFORM.getPlatformName().toLowerCase().contains("fabric") || Services.PLATFORM.isDevelopmentEnvironment()){
            installHook(Minecraft.class, "createTitle", new Class<?>[0],
                    "createTitleHookTestStr", new Class<?>[0], true);
        }

        // 关于 createTitle（private 实例方法）的实验结论，需要时把下面这段放开：
        //   installHook(Minecraft.class, "createTitle", new Class<?>[0],
        //           "createTitleHookTestStr", new Class<?>[0], true);
        // hookNonPublic 会把 hook 常量池的 pool_holder 改成 Minecraft，从而骗过 private 访问检查
        // （实测报错点从 createTitle 转移到 hook 自身对 private 成员的访问），但随后 JVM 会
        // 出现 EXCEPTION_ACCESS_VIOLATION。注意事项见 JHookUtils#hookNonPublic 的注释。
    }

    private static void installHook(Class<?> targetClazz, String targetName, Class<?>[] targetParams,
                                    String hookName, Class<?>[] hookParams, boolean unsafe) {
        try {
            Method original = targetClazz.getDeclaredMethod(targetName, targetParams);
            Method hook = CommonClass.class.getDeclaredMethod(hookName, hookParams);
            JHookUtils.probeMethodLayout(targetClazz, original);
            if (unsafe) {
                JHookUtils.hookNonPublic(targetClazz, original, CommonClass.class, hook);
            } else {
                JHookUtils.hookPublic(targetClazz, original, CommonClass.class, hook);
            }
        } catch (Throwable t) {
            Constants.LOG.error("[JHook] failed to install the hook on {}.{}: {}",
                    targetClazz.getSimpleName(), targetName, t.toString());
        }
    }

    /**
     * 与 {@code System.exit(int)} 同描述符、同为 static。
     * <p>跨类拿不到原版实现，所以直接用 {@code halt(0)} 达成「强制以 0 退出」。</p>
     */
    public static void exitForceZero(int status) {
        Constants.LOG.info("[JHook] System.exit({}) rewritten to Runtime.halt(0)", status);
        Runtime.getRuntime().halt(0);
    }

    /**
     * 与 {@code Minecraft.updateTitle()} 同描述符（{@code ()V}）。
     * <p><b>必须是实例方法</b>：目标方法把 {@code this} 放在局部变量槽 0，声明成 static 会整体错位。</p>
     * <p>运行时 {@code this} 是 {@code Minecraft} 实例，所以不能访问 {@code CommonClass} 的实例成员；
     * 用 {@code Minecraft.getInstance()} 这类静态入口是安全的。</p>
     */
    public void createTitleHookTest() {
        Minecraft.getInstance().getWindow().setTitle("JHook tested updateTitle");
    }

    /**
     * 若要试 {@code createTitle()}（返回 {@code String}），用这个方法做 hook 体。
     * <p>走 {@code hookUnsafe} 时 hook 的常量池 pool_holder 会被改成目标类，
     * 因此方法体<b>只能引用 public 成员或编译期常量</b>——这里的常量会被内联成 {@code ldc}。</p>
     */
    public String createTitleHookTestStr() {
        return "JHook: hooked createTitle";
    }
}
