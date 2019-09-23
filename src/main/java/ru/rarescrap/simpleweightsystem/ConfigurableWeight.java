package ru.rarescrap.simpleweightsystem;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
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
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerOpenContainerEvent;
import ru.rarescrap.weightapi.WeightRegistry;
import ru.rarescrap.weightapi.event.WeightChangedEvent;
import ru.rarescrap.weightapi.event.WeightProviderChangedEvent;

import java.io.File;
import java.util.List;

import static ru.rarescrap.simpleweightsystem.Utils.calculateAllowingStackSize;
import static ru.rarescrap.simpleweightsystem.Utils.drawCenteredStringWithoutShadow;

@Mod(modid = ConfigurableWeight.MODID, version = ConfigurableWeight.VERSION, dependencies = "required-after:weightapi@[0.4.0]")
public class ConfigurableWeight
{
    public static final String MODID = "configurableweight";
    public static final String VERSION = "0.4.0";

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
        if (configFile.exists()) {
            WeightRegistry.registerWeightProvider(MODID, configurableWeightProvider = new ConfigurableWeightProvider(configFile));
            FMLCommonHandler.instance().bus().register(configurableWeightProvider); // TODO: Разрегистрировать при удалении/замене в WeightRegistry
        } else throw new RuntimeException("[ConfigurableWeight] Can't find config file. Weights not loaded!");

    }

    // Присоединяем игрокам трекер инвентаря
    @SubscribeEvent
    public void onEntityConstructing(EntityEvent.EntityConstructing event) {
        if (event.entity instanceof EntityPlayerMP && PlayerWeightTracker.get((EntityPlayerMP) event.entity) == null)
            PlayerWeightTracker.register((EntityPlayerMP) event.entity);
    }

    // И присоединяем его к открытым контейнерам
    @SubscribeEvent
    public void onPlayerOpenContainer(PlayerOpenContainerEvent e) {
        PlayerWeightTracker tracker = PlayerWeightTracker.get((EntityPlayerMP) e.entityPlayer);
        /* Довольно узкое место. Дело в том, что PlayerOpenContainerEvent не совсем соотстветвует своему
         * описанию. Это скорее "CanInteractWithContainerEvent". Это из-за того, что этот евент по сути
         * выбрасывается каждый тик. А открываться контейнер каждый тик не может по логике.
         * Однако и этот "ущербный" эвент можно использовать в нужном ключе (в данном случае, для присоединения
         * слушателя изменения инвентаря (ICrafting). Только нужно позаботиться, чтобы он не добавлялся дважды. */
        if (!e.entityPlayer.openContainer.crafters.contains(tracker))
            tracker.attachListener(); // TODO: а если canInteractWith == false?
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!event.world.isRemote && event.entity instanceof EntityPlayer
                && WeightRegistry.getActiveWeightProvider() instanceof ConfigurableWeightProvider) {
            EntityPlayer player = (EntityPlayer) event.entity;
            if (WeightRegistry.getActiveWeightProvider().isOverloaded(player.inventory, player)) {
                // Minecraft не умеет сохранять кастомный PotionEffect и при загрузке наложит ванильный эффект
                player.removePotionEffect(Potion.moveSlowdown.id);
                player.addPotionEffect(new EndlessPotionEffect(Potion.moveSlowdown.id, 2));
            }
        }
    }

    // После достижения предела веса игрок не может подбирать предметы
    @SubscribeEvent
    public void onPickupItem(EntityItemPickupEvent event) {
        if (!(WeightRegistry.getActiveWeightProvider() instanceof ConfigurableWeightProvider)) return;

        EntityPlayer player = event.entityPlayer;
        boolean isOverloaded = WeightRegistry.getActiveWeightProvider().isOverloaded(player.inventory, player);
        if (isOverloaded) event.setCanceled(true);
        else {
            ItemStack itemStack = event.item.getEntityItem();
            double freeSpace = WeightRegistry.getActiveWeightProvider().getFreeSpace(player.inventory, player);
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
        if (event.entity.worldObj.isRemote || !(event.entity instanceof EntityPlayer)
                || !(WeightRegistry.getActiveWeightProvider() instanceof ConfigurableWeightProvider)) return;

        EntityPlayer player = (EntityPlayer) event.entity;
        // Флаг, обозначающий что на игрока уже наложен бесконечный эффект замедления
        boolean isSlowdown = EndlessPotionEffect.isPotionActive(player, Potion.moveSlowdown);

        // Если на игрока уже наложено бесконечное замедление - то добавлять его еще раз не имеет смысла
        if (event.isOverloaded && !isSlowdown) {
            player.removePotionEffect(Potion.moveSlowdown.id); // Удалим не бесконечный эффект, если таковой имеется TODO: Тогда игрок может намеренно перегрзить себя, а потом сбросить перегруз, чтобы отменить эффект зелья замедления. Пофикшу позднее.
            player.addPotionEffect(new EndlessPotionEffect(Potion.moveSlowdown.id, 2));
        } else if (!event.isOverloaded && isSlowdown) {
            // Удаляем бесконечный эффект замедения
            player.removePotionEffect(Potion.moveSlowdown.id);
        }
    }

    // Убираем бесконечное замедление при смене провайдера
    // onOverload() наложит эффект еще раз, если был включен провайдер-наследник
    @SubscribeEvent
    public void onWeightProviderChaged(WeightProviderChangedEvent.Pre event) {
        if (event.deactivatedProvider instanceof ConfigurableWeightProvider) {
            for (EntityPlayer player : (List<EntityPlayer>) event.world.playerEntities) {
                if (EndlessPotionEffect.isPotionActive(player, Potion.moveSlowdown))
                    player.removePotionEffect(Potion.moveSlowdown.id);
            }
        }
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void onRenderTooltip(ItemTooltipEvent event) {
        if (!(WeightRegistry.getActiveWeightProvider() instanceof ConfigurableWeightProvider)) return;

        // F3+H включает подробный тултип. В добавок к этому я буду выводить уникальные
        // идентификаторы итемов, чтобы юзеру было проще заполнять конфиг с весом.
        if (event.showAdvancedItemTooltips)
            event.toolTip.add(GameRegistry.findUniqueIdentifierFor(event.itemStack.getItem()).toString());

        double weight = WeightRegistry.getActiveWeightProvider().getWeight(event.itemStack, event.entityPlayer.inventory, event.entityPlayer);
        event.toolTip.add(StatCollector.translateToLocalFormatted("gui.itemstack_weight", weight));
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void renderWeight(GuiScreenEvent.DrawScreenEvent event) {
        if (!(WeightRegistry.getActiveWeightProvider() instanceof ConfigurableWeightProvider) // TODO: Отключаемый в конфиге рендер. Раз другие моды могут предоставлять собственный рендер веса, то каждый следует делать отключаемым. Боюсь, это самый лучший выход.
                || !(event.gui instanceof InventoryEffectRenderer)) return;

        InventoryEffectRenderer guiInventory = (InventoryEffectRenderer) event.gui;
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;

        double currentWeight = WeightRegistry.getActiveWeightProvider().getWeight(player.inventory, player);
        double maxWeight = WeightRegistry.getActiveWeightProvider().getMaxWeight(player.inventory, player);
        boolean isOverloaded = WeightRegistry.getActiveWeightProvider().isOverloaded(player.inventory, player);

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
