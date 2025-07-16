package net.luffy.sbwa;

import net.luffy.Newboy;
import net.luffy.sbwa.config.ConfigConfig;
import net.luffy.sbwa.handler.LgyzeroHandler;
import net.luffy.sbwa.handler.NewWeidianSenderHandler;
import net.luffy.sbwa.handler.WeidianHandler;
import net.luffy.sbwa.util.Common;
import net.mamoe.mirai.console.plugin.Plugin;
import net.mamoe.mirai.console.plugin.PluginManager;
import net.mamoe.mirai.console.plugin.jvm.JavaPlugin;
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescriptionBuilder;
import net.mamoe.mirai.event.GlobalEventChannel;

public final class NewboyWeidianAddon extends JavaPlugin {
    public static final NewboyWeidianAddon INSTANCE = new NewboyWeidianAddon();
    public static Newboy INSTANCE_NEWBOY;
    public static ConfigConfig config;
    public static WeidianHandler weidianHandler;
    public static LgyzeroHandler lgyzeroHandler;

    private NewboyWeidianAddon() {
        super(new JvmPluginDescriptionBuilder("net.luffy.newboyWA", "0.1.1-test7")
                .name("NewboyWeidianAddon")
                .author("delay")
                .dependsOn("net.luffy.newboy", true)
                .build());
    }

    @Override
    public void onEnable() {
        if (loadnewboy()) {
            initConfig();
            weidianHandler = new WeidianHandler();
            if (config.proxy_lgyzero) {
                lgyzeroHandler = new LgyzeroHandler();
            }

            INSTANCE_NEWBOY.handlerWeidianSender = new NewWeidianSenderHandler();
            GlobalEventChannel.INSTANCE.registerListenerHost(new listener());
        }
    }

    private void initConfig() {
        new Common(getConfigFolder());
        config = new ConfigConfig(resolveConfigFile("config.setting"));
    }

    private boolean loadnewboy() {
        for (Plugin plugin : PluginManager.INSTANCE.getPlugins()) {
            if (plugin instanceof Newboy) {
                INSTANCE_NEWBOY = (Newboy) plugin;
                getLogger().info("读取Newboy插件成功");
                return true;
            }
        }
        getLogger().info("读取Newboy插件失败");
        return false;
    }
}
