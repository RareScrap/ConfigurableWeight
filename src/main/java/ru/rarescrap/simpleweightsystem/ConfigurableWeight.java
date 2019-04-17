package ru.rarescrap.simpleweightsystem;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import ru.rarescrap.weightapi.WeightRegistry;
import ru.rarescrap.weightapi.event.WeightChangedEvent;

import java.io.File;

import static ru.rarescrap.simpleweightsystem.Utils.calculateAllowingStackSize;
import static ru.rarescrap.simpleweightsystem.Utils.drawCenteredStringWithoutShadow;

@Mod(modid = ConfigurableWeight.MODID, version = ConfigurableWeight.VERSION, dependencies = "required-after:weightapi")
public class ConfigurableWeight
{
    public static final String MODID = "configurableweight";
    public static final String VERSION = "1.0";

    public static final SimpleNetworkWrapper NETWORK = NetworkRegistry.INSTANCE.newSimpleChannel(MODID.toLowerCase());

    public static ConfigurableWeightProvider configurableWeightProvider;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        NETWORK.registerMessage(ConfigurableWeightProvider.MessageHandler.class,
                ConfigurableWeightProvider.SyncMessage.class,0, Side.CLIENT);
    }

    // Читам конфиг веса на сервере
    @EventHandler
    public void onServerStart(FMLServerAboutToStartEvent event) {
        File configFile = new File(Loader.instance().getConfigDir(), MODID+".cfg");
        if (configFile.exists()) WeightRegistry.registerWeightProvider(configurableWeightProvider = new ConfigurableWeightProvider(configFile));
        else throw new RuntimeException("[ConfigurableWeight] Can't find config file. Weights not loaded!");
    }

    @Mod.EventHandler
    public void onServerStop(FMLServerStoppingEvent event) {
        PlayerWeightTracker.clearTrackers();
    }

    // Высылаем клиенту таблицу весов, если тот подключился
    @SubscribeEvent
    public void onClientConectToServer(PlayerEvent.PlayerLoggedInEvent event) {
        if (!event.player.worldObj.isRemote)
            // Ничего страшного, что в сингле пошлется пакет, который по факту ничего не изменит.
            // Стоимоть пакета в сингле почти нулевая.
            NETWORK.sendTo(new ConfigurableWeightProvider.SyncMessage(configurableWeightProvider), (EntityPlayerMP) event.player);
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.entity instanceof EntityPlayer && !event.world.isRemote) {
            EntityPlayer player = (EntityPlayer) event.entity;
            PlayerWeightTracker.registerTracker(player);

            if (WeightRegistry.getWeightProvider().isOverloaded(player.inventory, player)) {
                player.removePotionEffect(Potion.moveSlowdown.id); // TODO: Без хуков лучше не сделаешь. По крайней мере, я не знаю как.
                player.addPotionEffect(new EndlessPotionEffect(Potion.moveSlowdown.id, 2));
            }
        }
    }

    // После достижения предела веса игрок не может подбирать предметы
    @SubscribeEvent
    public void onPickupItem(EntityItemPickupEvent event) {
        EntityPlayer player = event.entityPlayer;
        boolean isOverloaded = WeightRegistry.getWeightProvider().isOverloaded(player.inventory, player);
        if (isOverloaded) event.setCanceled(true);
        else {
            ItemStack itemStack = event.item.getEntityItem();
            double freeSpace = WeightRegistry.getWeightProvider().getFreeSpace(player.inventory, player);
            int takenItems = calculateAllowingStackSize(itemStack, player.inventory, player, freeSpace);
            if (takenItems > 0) {
                itemStack.stackSize -= takenItems;
                player.inventory.addItemStackToInventory(
                        new ItemStack(itemStack.getItem(), takenItems, itemStack.getItemDamage()));
            }
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onOverload(WeightChangedEvent event) {
        if (event.entity.worldObj.isRemote || !(event.entity instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) event.entity;
        // Флаг, обозначающий что на игрока уже наложен бесконечный эффект замедления
        boolean isSlowdown = ((EntityPlayer) event.entity).isPotionActive(Potion.moveSlowdown)
                && player.getActivePotionEffect(Potion.moveSlowdown) instanceof EndlessPotionEffect;

        // Если на игрока уже наложено бесконечное замедление - то добавлять его еще раз не имеет смысла
        if (event.isOverloaded && !isSlowdown) {
            player.removePotionEffect(Potion.moveSlowdown.id); // Удалим не бесконечный эффект, если таковой имеется TODO: Тогда игрок может намеренно перегрзить себя, а потом сбросить перегруз, чтобы отменить эффект зелья замедления. Пофикшу позднее.
            player.addPotionEffect(new EndlessPotionEffect(Potion.moveSlowdown.id, 2));
        } else if (!event.isOverloaded && isSlowdown) {
            // Удаляем бесконечный эффект замедения
            player.removePotionEffect(Potion.moveSlowdown.id);
        }
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void onRenderTooltip(ItemTooltipEvent event) {
        // F3+H включает подробный тултип. В добавок к этому я буду выводить уникальные
        // идентификаторы итемов, чтобы юзеру было проще заполнять конфиг с весом.
        if (event.showAdvancedItemTooltips)
            event.toolTip.add(GameRegistry.findUniqueIdentifierFor(event.itemStack.getItem()).toString());

        double weight = WeightRegistry.getWeightProvider().getWeight(event.itemStack, event.entityPlayer.inventory, event.entityPlayer);
        event.toolTip.add(StatCollector.translateToLocalFormatted("gui.itemstack_weight", weight));
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void renderWeight(GuiScreenEvent.DrawScreenEvent event) {
        if (!(event.gui instanceof InventoryEffectRenderer)) return;

        InventoryEffectRenderer guiInventory = (InventoryEffectRenderer) event.gui;
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;

        double currentWeight = WeightRegistry.getWeightProvider().getWeight(player.inventory, player);
        double maxWeight = WeightRegistry.getWeightProvider().getMaxWeight(player.inventory, player);
        boolean isOverloaded = WeightRegistry.getWeightProvider().isOverloaded(player.inventory, player);

        String str = StatCollector.translateToLocalFormatted("gui.inventory_weight", currentWeight, maxWeight);
        int color = isOverloaded ? 0xDB1818 : 4210752;

        // Рендерим строку веса в инвентаре
        if (guiInventory instanceof GuiInventory)
            drawCenteredStringWithoutShadow(Minecraft.getMinecraft().fontRenderer, str, guiInventory.guiLeft + 125, guiInventory.guiTop + 70, color);
        // В креативе вес показывается только на вкладке с инвентарем (11-ая вкладка)
        else if (guiInventory instanceof GuiContainerCreative && ((GuiContainerCreative) guiInventory).func_147056_g() == 11) {
            drawCenteredStringWithoutShadow(Minecraft.getMinecraft().fontRenderer, str, guiInventory.guiLeft + 125, guiInventory.guiTop + 40, color);
        }
    }
}
