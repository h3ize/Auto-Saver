package com.autobackup.net;

import com.autobackup.AutoBackup;
import com.autobackup.client.IndicatorState;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SaveStatePayload(boolean active) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SaveStatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AutoBackup.MODID, "save_state"));

    public static final StreamCodec<ByteBuf, SaveStatePayload> STREAM_CODEC =
            ByteBufCodecs.BOOL.map(SaveStatePayload::new, SaveStatePayload::active);

    @Override
    public CustomPacketPayload.Type<SaveStatePayload> type() {
        return TYPE;
    }

    public static void handle(SaveStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> IndicatorState.set(payload.active()));
    }
}
