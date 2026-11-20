package cn.ksmcbrigade.jhookmod.mixin;

import cn.ksmcbrigade.jhookmod.Constants;
import cn.ksmcbrigade.jhookmod.platform.Services;
import cn.ksmcbrigade.jhookmod.utils.agent.AgentUtils;
import cn.ksmcbrigade.jhookmod.utils.agent.UnsafeUtils;
import cn.ksmcbrigade.jhookmod.utils.ffm.FFMUtils;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class JHookMixinPlugin implements IMixinConfigPlugin {

    static {

        try {
            Constants.LOG.info("JavaAgent injected: {}",AgentUtils.injectTmpAgent());
        } catch (Throwable e) {
            Constants.LOG.error("Failed to inject JavaAgent for FFM", e);
        }
        try {
            UnsafeUtils.enableNativeAccess(Constants.LOG);
        } catch (Throwable e) {
            Constants.LOG.error("Failed to enable native access for FFM", e);
        }

        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            try {
                UnsafeUtils.debugInfoNativeAccess(Constants.LOG);
            } catch (Throwable e) {
                Constants.LOG.warn("debugInfoNativeAccess failed", e);
            }
            FFMUtils.debugNativeAccess();
        }

        try {
            FFMUtils.tryGetJavaVm();
        } catch (Throwable e) {
            throw new IllegalStateException("JVMTI injection failed", e);
        }
    }

    @Override
    public void onLoad(String s) {

    }

    @Override
    public String getRefMapperConfig() {
        return "";
    }

    @Override
    public boolean shouldApplyMixin(String s, String s1) {
        return false;
    }

    @Override
    public void acceptTargets(Set<String> set, Set<String> set1) {

    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void postApply(String s, org.objectweb.asm.tree.ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

    @Override
    public void preApply(String s, org.objectweb.asm.tree.ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }
}
