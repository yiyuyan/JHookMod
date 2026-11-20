package cn.ksmcbrigade.jhookmod.platform;

import cn.ksmcbrigade.jhookmod.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        try {
            return !FMLLoader.isProduction();
        } catch (Throwable e) {
            return false;
        }
    }
}
