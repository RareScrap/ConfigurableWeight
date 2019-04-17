package ru.rarescrap.simpleweightsystem;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import ru.rarescrap.weightapi.WeightRegistry;
import ru.rarescrap.weightapi.event.WeightChangedEvent;

import java.util.HashMap;

// TODO: Заменить на хуки. А то надоел этот зоопарк евентов.
public class PlayerWeightTracker {
    // TODO: а что делать с тайлами?
    public static HashMap<EntityPlayer, PlayerWeightTracker> trackers = new HashMap<EntityPlayer, PlayerWeightTracker>();

    EntityPlayer player;
    double prevWeight;
    @SideOnly(Side.CLIENT)
    double prevWeightClient;

    public static void registerTracker(EntityPlayer player) {
        trackers.put(player, new PlayerWeightTracker(player));
        // debug
        if (trackers.size() != FMLCommonHandler.instance().getMinecraftServerInstance().getCurrentPlayerCount())
            System.err.println("Ебать косяк! Походу лишний трекер");
    }

    private PlayerWeightTracker(EntityPlayer player) {
        this.player = player;
        setPrevWeight(WeightRegistry.getWeightProvider().getWeight(player.inventory, player), player.worldObj); // TODO: ПОчему на клиенте не срабатывает?
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.PlayerTickEvent event) {
        double currentWeight = WeightRegistry.getWeightProvider().getWeight(event.player.inventory, event.player);
        boolean isOverloaded = WeightRegistry.getWeightProvider().isOverloaded(event.player.inventory, event.player);
        double prevWeight = getPrevWeight(event.player.worldObj);
        if (currentWeight != prevWeight) {
            MinecraftForge.EVENT_BUS.post(new WeightChangedEvent(event.player.inventory,
                    prevWeight, currentWeight, isOverloaded, event.player));
            setPrevWeight(currentWeight, event.player.worldObj);
        }
    }

    @SubscribeEvent
    public void onClonePlayer(PlayerEvent.Clone event) {
        if (event.original == player) {
            MinecraftForge.EVENT_BUS.unregister(this);
            FMLCommonHandler.instance().bus().unregister(this);
            trackers.remove(player);
        }
    }

    @SubscribeEvent
    public void onDisconnect(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player == player) {
            MinecraftForge.EVENT_BUS.unregister(this);
            FMLCommonHandler.instance().bus().unregister(this);
            trackers.remove(player);
        }
    }

    @SubscribeEvent
    public void onPlayerDead(LivingDeathEvent event) {
        if (event.entity == player) {
            MinecraftForge.EVENT_BUS.unregister(this);
            FMLCommonHandler.instance().bus().unregister(this);
            trackers.remove(player);
        }
    }

    public static void clearTrackers() {
        for (PlayerWeightTracker tracker : trackers.values()) {
            MinecraftForge.EVENT_BUS.unregister(tracker);
            FMLCommonHandler.instance().bus().unregister(tracker);
        }
        trackers.clear();
    }


    private double getPrevWeight(World world) {
        if (world.isRemote) return prevWeightClient;
        else return prevWeight;
    }

    private void setPrevWeight(double weight, World world) {
        if (world.isRemote) prevWeightClient = weight;
        else prevWeight = weight;
    }
}
