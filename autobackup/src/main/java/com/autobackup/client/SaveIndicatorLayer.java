package com.autobackup.client;

import com.autobackup.AutoBackup;
import com.autobackup.ClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class SaveIndicatorLayer {
    private static final ResourceLocation CHEST =
            ResourceLocation.fromNamespaceAndPath(AutoBackup.MODID, "textures/gui/chest.png");
    private static final ResourceLocation ARROW =
            ResourceLocation.fromNamespaceAndPath(AutoBackup.MODID, "textures/gui/arrow.png");

    private static final int WIDTH = 32;
    private static final int HEIGHT = 52;
    private static final int CHEST_SIZE = 32;
    private static final int CHEST_Y = 20;
    private static final int ARROW_SIZE = 16;
    private static final int ARROW_X = 8;
    private static final float BOB_RANGE = 4.0F;
    private static final int VANILLA_CLEARANCE = 28;

    private SaveIndicatorLayer() {
    }

    private static boolean vanillaIndicatorPossible(Minecraft minecraft) {
        return minecraft.getSingleplayerServer() != null && minecraft.options.showAutosaveIndicator().get();
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!ClientConfig.SHOW_ICON.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) {
            return;
        }
        float alpha = IndicatorState.alpha();
        if (alpha <= 0.0F) {
            return;
        }

        float scale = ClientConfig.SCALE.get().floatValue();
        int margin = ClientConfig.MARGIN.get();
        float width = WIDTH * scale;
        float height = HEIGHT * scale;
        ClientConfig.Corner corner = ClientConfig.CORNER.get();

        float x = switch (corner) {
            case BOTTOM_LEFT, TOP_LEFT -> margin;
            case BOTTOM_RIGHT, TOP_RIGHT -> graphics.guiWidth() - margin - width;
        };
        float y = switch (corner) {
            case TOP_LEFT, TOP_RIGHT -> margin;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> graphics.guiHeight() - margin - height;
        };
        if (corner == ClientConfig.Corner.BOTTOM_RIGHT && vanillaIndicatorPossible(minecraft)) {
            y -= VANILLA_CLEARANCE;
        }

        int arrowY = ClientConfig.BOB.get() ? Math.round(IndicatorState.bob() * BOB_RANGE) : 2;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0F);
        pose.scale(scale, scale, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.blit(CHEST, 0, CHEST_Y, 0, 0, CHEST_SIZE, CHEST_SIZE, CHEST_SIZE, CHEST_SIZE);
        graphics.blit(ARROW, ARROW_X, arrowY, 0, 0, ARROW_SIZE, ARROW_SIZE, ARROW_SIZE, ARROW_SIZE);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        pose.popPose();
    }
}
