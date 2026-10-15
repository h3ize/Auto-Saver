package com.autobackup;

import com.autobackup.client.ClientSetup;
import com.autobackup.net.SaveStatePayload;
import com.autobackup.server.AutoBackupManager;
import com.autobackup.server.AutoSaveCommand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(AutoBackup.MODID)
public class AutoBackup {
    public static final String MODID = "autobackup";

    public AutoBackup(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "autobackup-common.toml");
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "autobackup-client.toml");
        modBus.addListener(AutoBackup::registerPayloads);
        NeoForge.EVENT_BUS.register(AutoBackupManager.class);
        NeoForge.EVENT_BUS.register(AutoSaveCommand.class);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modBus);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(SaveStatePayload.TYPE, SaveStatePayload.STREAM_CODEC, SaveStatePayload::handle);
    }
}
