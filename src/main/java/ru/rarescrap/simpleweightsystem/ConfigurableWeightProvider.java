package ru.rarescrap.simpleweightsystem;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
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
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
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

import static ru.rarescrap.simpleweightsystem.ConfigurableWeight.MODID;
import static ru.rarescrap.simpleweightsystem.ConfigurableWeight.NETWORK;

/**
 * Система веса с возможностью задавать вес каждого предмета через конфигурационный файл.
 * За счет синхронизации с клиентом может работать также на стороне клиенте без доступа к серверу.
 */
public class ConfigurableWeightProvider implements IWeightProvider {

    private Configuration config; // TODO: ненужно поле
    protected Map<Item, Double> weightStorage = new HashMap<Item, Double>();
    protected double defaultWeight;

    @SideOnly(Side.CLIENT)
    public ConfigurableWeightProvider(Map<Item, Double> weightStorage, double defaultWeight) {
        this.weightStorage = weightStorage;
        this.defaultWeight = defaultWeight;
    }

    public ConfigurableWeightProvider(File configFile) {
        config = new Configuration(configFile);
        readConfig();
    }

    protected void readConfig() {
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

    protected void readItems(ConfigCategory itemCategory) {
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

    // Высылаем клиенту таблицу весов, если тот подключился
    @SubscribeEvent
    public void onClientConectToServer(PlayerEvent.PlayerLoggedInEvent event) {
        if (!event.player.worldObj.isRemote
                // Если игрок в одиноче - не высылаем пакет, т.к. клиентский и серверный поток
                // работают на одной машине и указывают на одно место в памяти
                && (MinecraftServer.getServer().isDedicatedServer() || ((IntegratedServer) MinecraftServer.getServer()).getPublic())
                && WeightRegistry.getActiveWeightProvider() instanceof ConfigurableWeightProvider)
            NETWORK.sendTo(new ConfigurableWeightProvider.SyncMessage(this), (EntityPlayerMP) event.player);
    }

    /**
     * Пакет, доставляющий таблицу весов на клиент
     */
    public static class SyncMessage implements IMessage {
        ConfigurableWeightProvider weightProvider;

        public SyncMessage() {} // for reflection newInstance()

        public SyncMessage(ConfigurableWeightProvider serverWeightProvider) {
            this.weightProvider = serverWeightProvider;
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
            weightProvider = new ConfigurableWeightProvider(weightStorage, defaultWeight);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeVarInt(buf, weightProvider.weightStorage.size(), 1);
            for (Map.Entry<Item, Double> entry : weightProvider.weightStorage.entrySet()) {
                ByteBufUtils.writeVarShort(buf, Item.getIdFromItem(entry.getKey()));
                buf.writeDouble(entry.getValue());
            }
            buf.writeDouble(weightProvider.defaultWeight);
        }
    }

    public static class MessageHandler implements IMessageHandler<SyncMessage, IMessage> {
        @SideOnly(Side.CLIENT)
        @Override
        public IMessage onMessage(SyncMessage message, MessageContext ctx) {
            WeightRegistry.registerWeightProvider(MODID, message.weightProvider);
            WeightRegistry.activateWeightProvider(MODID, Minecraft.getMinecraft().theWorld);
            return null;
        }
    }
}
