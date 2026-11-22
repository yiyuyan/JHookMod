package cn.ksmcbrigade.jhookmod;

import net.minecraft.client.Minecraft;

/**
 * 在 {@code createTitle} hook 安装之后刷新窗口标题。
 *
 * <p>MC 26.1 Fabric 在 mod 入口之前就已经调用过 {@code Minecraft.createTitle()} 并把结果传给
 * {@code Window}，所以 hook 装好时窗口标题仍是旧值。这里等窗口就绪后在客户端线程调用
 * {@code updateTitle()}，它会再走一次（已经被换过的）{@code createTitle()}，让 hook 的效果可见。</p>
 *
 * <p>单独成类的原因：{@code JHookUtils} 会给被 hook 的方法改写 {@code ConstMethod}（含改名、
 * 换 {@code pool_holder}），之后在同一个类里再按名字解析新的静态方法在部分 JDK 上会失败
 * （实测 26.1 会抛 {@code NoSuchMethodError}），放到独立类里就没有这个顺序问题。</p>
 */
public final class TitleRefresh {

    private TitleRefresh() {
    }

    public static void refresh() {
        Thread thread = new Thread(TitleRefresh::run, "JHook-TitleRefresh");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> {
                    try {
                        minecraft.updateTitle();
                        Constants.LOG.info("[JHookDemo] window title refreshed after createTitle hook");
                    } catch (Throwable t) {
                        Constants.LOG.warn("[JHookDemo] updateTitle failed: {}", t.toString());
                    }
                });
                return;
            } catch (Throwable ignored) {

            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Constants.LOG.warn("[JHookDemo] window was not ready, skipped the window title refresh");
    }
}
