package de.bytemc.cloud.plugin.player;

import de.bytemc.cloud.api.network.packets.player.CloudPlayerDisconnectPacket;
import de.bytemc.cloud.api.network.packets.player.CloudPlayerLoginPacket;
import de.bytemc.cloud.api.player.ICloudPlayer;
import de.bytemc.cloud.api.player.impl.AbstractPlayerManager;
import de.bytemc.cloud.api.player.impl.SimpleCloudPlayer;
import de.bytemc.cloud.plugin.CloudPlugin;
import de.bytemc.cloud.plugin.services.ServiceManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class CloudPlayerManager extends AbstractPlayerManager {

    @Override
    public @NotNull List<ICloudPlayer> getAllServicePlayers() {
        return this.getAllCachedCloudPlayers().stream()
            .filter(it -> it.getServer().getName().equalsIgnoreCase(((ServiceManager) CloudPlugin.getInstance().getServiceManager())
                .thisService().getName())).collect(Collectors.toList());
    }

    @Override
    public void registerCloudPlayer(@NotNull UUID uniqueID, @NotNull String username) {
        getAllCachedCloudPlayers().add(new SimpleCloudPlayer(uniqueID, username));
        CloudPlugin.getInstance().getPluginClient().sendPacket(new CloudPlayerLoginPacket(username, uniqueID));
    }

    @Override
    public void unregisterCloudPlayer(@NotNull UUID uuid, @NotNull String username) {
        getAllCachedCloudPlayers().remove(getCloudPlayerByUniqueIdOrNull(uuid));
        CloudPlugin.getInstance().getPluginClient().sendPacket(new CloudPlayerDisconnectPacket(uuid, username));
    }

}
