package com.autobackup.client;

import com.autobackup.AutoBackup;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class ClientSetup {
    private ClientSetup() {
    }

    public static void init(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(ClientSetup::registerLayers);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onLoggingOut);
    }

    private static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(AutoBackup.MODID, "save_indicator"),
                SaveIndicatorLayer::render);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        IndicatorState.reset();
    }
}
