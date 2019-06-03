package ru.rarescrap.simpleweightsystem;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;
import net.minecraftforge.common.MinecraftForge;
import ru.rarescrap.weightapi.WeightRegistry;
import ru.rarescrap.weightapi.event.WeightChangedEvent;

import java.util.List;

/**
 * Механизм, отслеживающий изменения инвентаря игрока.
 * Работает только на сервере, т.к. пока не имеет необходиости работать на клиенте.
 */
public class PlayerWeightTracker implements ICrafting, IExtendedEntityProperties {
    private static final String EXTENDED_ENTITY_TAG = "weight_tracker_data";

    /** Игрок, инвентарь которого отслеживается */
    private EntityPlayerMP entityPlayer;
    /** Вес {@link EntityPlayer#inventory} до изменения его содержмого */
    private double prevWeight = 0D;

    private PlayerWeightTracker(EntityPlayerMP entityPlayer) {
        this.entityPlayer = entityPlayer;
    }

    @Override
    public void sendContainerAndContentsToPlayer(Container p_71110_1_, List p_71110_2_) {
        /* Момент, когда игроку на клиенте высылается содержимое контейнера, очень удобно
           использовать для вычисления веса до момента изменения содержимого инвентаря */
        updatePrevWeight();
    }

    @Override
    public void sendSlotContents(Container p_71111_1_, int p_71111_2_, ItemStack p_71111_3_) {
        double currentWeight = getCurrentWeight();
        WeightChangedEvent event = new WeightChangedEvent(
                entityPlayer.inventory,
                prevWeight,
                currentWeight,
                WeightRegistry.getWeightProvider().isOverloaded(entityPlayer.inventory, entityPlayer),
                entityPlayer
        );
        // Шлем эвент об изменении веса инвентаря TODO: Проверка на prevWeight != currentWeight
        MinecraftForge.EVENT_BUS.post(event);
        prevWeight = currentWeight; // Обновляем предыдущий вес
    }

    @Override
    public void sendProgressBarUpdate(Container p_71112_1_, int p_71112_2_, int p_71112_3_) {}

    /**
     * Присоединяет трекер к открытому контейнеру игрока
     */
    public void attachListener() {
        entityPlayer.openContainer.addCraftingToCrafters(this); // TODO: А что с удалением?
    }

    public static final void register(EntityPlayerMP player) {
        PlayerWeightTracker weightData = new PlayerWeightTracker(player);
        player.registerExtendedProperties(EXTENDED_ENTITY_TAG, weightData);
        weightData.init(player, player.worldObj);
    }

    public static final PlayerWeightTracker get(EntityPlayerMP player) {
        return (PlayerWeightTracker) player.getExtendedProperties(EXTENDED_ENTITY_TAG); // TODO: Добавить Exception, если null
    }

    public void updatePrevWeight() {
        prevWeight = WeightRegistry.getWeightProvider().getWeight(entityPlayer.inventory, entityPlayer);
    }

    public double getCurrentWeight() {
        return WeightRegistry.getWeightProvider().getWeight(entityPlayer.inventory, entityPlayer);
    }

    @Override
    public void saveNBTData(NBTTagCompound compound) {}

    @Override
    public void loadNBTData(NBTTagCompound compound) {}

    @Override
    public void init(Entity entity, World world) {
        entityPlayer = (EntityPlayerMP) entity; // Обновим игрока, дабы в IEEP хранился актуальный игрок
    }
}
