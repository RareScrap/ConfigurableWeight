package ru.rarescrap.simpleweightsystem;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
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
    }

    public EndlessPotionEffect(PotionEffect potionEffect) {
        this(potionEffect.getPotionID(), potionEffect.getAmplifier(), potionEffect.getIsAmbient());
    }

    @Override
    public int deincrementDuration() {
        return this.duration;
    }

    public static EndlessPotionEffect get(EntityLivingBase entityLivingBase, Potion potion) {
        PotionEffect effect = entityLivingBase.getActivePotionEffect(potion);
        if (effect instanceof EndlessPotionEffect)
            return (EndlessPotionEffect) effect;
        return null;
    }

    public static boolean isPotionActive(EntityLivingBase entityLivingBase, Potion potion) {
        return get(entityLivingBase, potion) != null;
    }
}
