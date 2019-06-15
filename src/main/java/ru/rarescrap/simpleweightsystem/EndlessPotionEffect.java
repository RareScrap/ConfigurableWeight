package ru.rarescrap.simpleweightsystem;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.potion.PotionEffect;
import net.minecraftforge.common.MinecraftForge;
import ru.rarescrap.weightapi.event.WeightProviderChangedEvent;

/**
 * {@link PotionEffect} с бесконечным сроком работы
 */
public class EndlessPotionEffect extends PotionEffect {
    public EndlessPotionEffect(int potionID) {
        this(potionID, 0);
    }

    public EndlessPotionEffect(int potionID, int amplifier) {
        this(potionID, amplifier, false);
    }

    public EndlessPotionEffect(int potionID, int amplifier, boolean isAmbient) {
        super(potionID, 1, amplifier, isAmbient);
        getCurativeItems().clear(); // Убираем возможность снять эффект молоком (или другим лекарством)
        MinecraftForge.EVENT_BUS.register(this);
    }

    public EndlessPotionEffect(PotionEffect potionEffect) {
        this(potionEffect.getPotionID(), potionEffect.getAmplifier(), potionEffect.getIsAmbient());
    }

    @Override
    public int deincrementDuration() {
        return this.duration;
    }

    // Эффект бесконечного замедления работает только для ConfigurableWeightProvider.
    // Поэтому убираем эффект, если новая система веса - не ConfigurableWeightProvider.
    @SubscribeEvent
    public void onWeightProviderChanged(WeightProviderChangedEvent.Pre event) {
        if (event.currentProvider instanceof ConfigurableWeightProvider) return;

        this.duration = 0; // В следующим тике эффект закончится
        MinecraftForge.EVENT_BUS.unregister(this);
    }
}
