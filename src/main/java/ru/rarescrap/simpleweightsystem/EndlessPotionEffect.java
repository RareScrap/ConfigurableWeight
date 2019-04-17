package ru.rarescrap.simpleweightsystem;

import net.minecraft.potion.PotionEffect;

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
        super(potionEffect);
    }

    @Override
    public int deincrementDuration() {
        return this.duration;
    }
}
