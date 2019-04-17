package ru.rarescrap.simpleweightsystem;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import ru.rarescrap.weightapi.IWeightProvider;
import ru.rarescrap.weightapi.WeightRegistry;
import ru.rarescrap.weightapi.event.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ConfigurableWeightProvider implements IWeightProvider {

    private Configuration config;
    private Map<Item, Double> weightStorage = new HashMap<Item, Double>();
    private double defaultWeight;

    @SideOnly(Side.CLIENT)
    public ConfigurableWeightProvider(Map<Item, Double> weightStorage, double defaultWeight) {
        this.weightStorage = weightStorage;
        this.defaultWeight = defaultWeight;
    }

    public ConfigurableWeightProvider(File configFile) {
        config = new Configuration(configFile);
        readConfig();
    }

    private void readConfig() {
        defaultWeight = config.get("default", "weight", 1).getDouble();
        for (String categoryName : config.getCategoryNames()) {
            // Обрабатываем категорию с дефолтными настройками
            if (categoryName.equals("default")) {
                defaultWeight = config.get(categoryName, "weight", 1).getDouble();
                continue;
            }

            // И категории с предметами
            ConfigCategory category = config.getCategory(categoryName);
            readItems(category);
        }
    }

    private void readItems(ConfigCategory itemCategory) {
        for (Property itemProperty : itemCategory.getOrderedValues()) {
            Item item = GameRegistry.findItem(itemCategory.getName(), itemProperty.getName());
            if (item == null) throw new RuntimeException("There no item with name \"" + itemProperty.getName() + "\""); // Чтобы юзеру было понятнее
            weightStorage.put(item, itemProperty.getDouble());
        }
    }

    @Override
    public double getWeight(ItemStack itemStack, IInventory inventory, Entity owner) {
        if (itemStack == null) return 0;
        double weight = weightStorage.containsKey(itemStack.getItem()) ? weightStorage.get(itemStack.getItem()) : defaultWeight;
        weight *= itemStack.stackSize;

        CalculateStackWeightEvent event = new CalculateStackWeightEvent(itemStack, inventory, weight, owner);
        MinecraftForge.EVENT_BUS.post(event);
        return event.weight;
    }

    @Override
    public double getWeight(IInventory inventory, Entity owner) {
        double inventoryWeight = 0;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            inventoryWeight += getWeight(inventory.getStackInSlot(i), inventory, owner);
        }

        CalculateInventoryWeightEvent event = new CalculateInventoryWeightEvent(inventory, inventoryWeight, owner);
        MinecraftForge.EVENT_BUS.post(event);
        return event.weight;
    }

    @Override
    public boolean isOverloaded(IInventory inventory, Entity owner) {
        boolean isOverloaded =  getWeight(inventory, owner) > getMaxWeight(inventory, owner);

        CalculateOverloadEvent event = new CalculateOverloadEvent(inventory, isOverloaded, owner);
        MinecraftForge.EVENT_BUS.post(event);
        return event.isOverload;
    }

    @Override
    public double getFreeSpace(IInventory inventory, Entity owner) {
        double freeSpace = getMaxWeight(inventory, owner) - getWeight(inventory, owner);

        CalculateFreeSpaceEvent event = new CalculateFreeSpaceEvent(inventory, freeSpace, owner);
        MinecraftForge.EVENT_BUS.post(event);
        return event.freeSpace;
    }

    @Override
    public double getMaxWeight(IInventory inventory, Entity owner) {
        double maxWeight;
        if (owner instanceof EntityPlayer) maxWeight = 5 + (((EntityPlayer) owner).experienceLevel * 10);
        else maxWeight = inventory.getSizeInventory() * 64;

        CalculateMaxWeightEvent event = new CalculateMaxWeightEvent(inventory, maxWeight, owner);
        MinecraftForge.EVENT_BUS.post(event);
        return event.maxWeight;
    }

    /**
     * Пакет, доставляющий таблицу весов на клиент
     */
    public static class SyncMessage implements IMessage {
        @SideOnly(Side.CLIENT)
        ConfigurableWeightProvider clientWeightProvider;
        ConfigurableWeightProvider serverWeightProvider;

        // for reflection new instance
        public SyncMessage() {}

        public SyncMessage(ConfigurableWeightProvider serverWeightProvider) {
            this.serverWeightProvider = serverWeightProvider;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            Map<Item, Double> weightStorage = new HashMap<Item, Double>();
            int size = ByteBufUtils.readVarInt(buf, 1);
            for (int i = 0; i < size; i++) {
                Item item = Item.getItemById(ByteBufUtils.readVarShort(buf));
                double weight = buf.readDouble();
                weightStorage.put(item, weight);
            }
            double defaultWeight = buf.readDouble();
            clientWeightProvider = new ConfigurableWeightProvider(weightStorage, defaultWeight);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeVarInt(buf, serverWeightProvider.weightStorage.size(), 1);
            for (Map.Entry<Item, Double> entry : serverWeightProvider.weightStorage.entrySet()) {
                ByteBufUtils.writeVarShort(buf, Item.getIdFromItem(entry.getKey()));
                buf.writeDouble(entry.getValue());
            }
            buf.writeDouble(serverWeightProvider.defaultWeight);
        }
    }

    public static class MessageHandler implements IMessageHandler<SyncMessage, IMessage> {

        @SideOnly(Side.CLIENT)
        @Override
        public IMessage onMessage(SyncMessage message, MessageContext ctx) {
            if (WeightRegistry.getWeightProvider() == null ||
                    WeightRegistry.getWeightProvider() != ConfigurableWeight.configurableWeightProvider) // Эта проверку нужна, когда игрок в сингле
                WeightRegistry.registerWeightProvider(message.clientWeightProvider);
            return null;
        }
    }
}
