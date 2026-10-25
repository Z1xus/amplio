package us.z1x.amplio.gui;

import org.embeddedt.embeddium.impl.gui.framework.TextComponent;
import org.taumc.celeritas.api.options.OptionIdentifier;
import org.taumc.celeritas.api.options.control.CyclingControl;
import org.taumc.celeritas.api.options.control.SliderControl;
import org.taumc.celeritas.api.options.structure.OptionGroup;
import org.taumc.celeritas.api.options.structure.OptionImpact;
import org.taumc.celeritas.api.options.structure.OptionImpl;
import org.taumc.celeritas.api.options.structure.OptionPage;
import org.taumc.celeritas.api.options.structure.OptionStorage;
import us.z1x.amplio.Amplio;
import us.z1x.amplio.AmplioConfig;
import us.z1x.amplio.DlssModel;
import us.z1x.amplio.Quality;
import us.z1x.amplio.UpscalerType;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

// the amplio page in argentum's video settings
public final class AmplioOptionPage {
    private static final OptionStorage<AmplioConfig> STORAGE = new OptionStorage<>() {
        @Override
        public AmplioConfig getData() {
            return Amplio.CONFIG;
        }

        @Override
        public void save() {
            Amplio.CONFIG.save();
        }
    };

    private AmplioOptionPage() {
    }

    public static OptionPage create() {
        OptionGroup group = OptionGroup.createBuilder()
            .setId(OptionIdentifier.create("amplio", "upscaling"))
            .add(OptionImpl.createBuilder(UpscalerType.class, STORAGE)
                .setId(OptionIdentifier.create("amplio", "upscaler", UpscalerType.class))
                .setName(TextComponent.translatable("amplio.options.upscaler"))
                .setTooltip(TextComponent.translatable("amplio.options.upscaler.tooltip"))
                .setControl(option -> new CyclingControl<>(option, UpscalerType.class, names("amplio.upscaler.", UpscalerType.values())))
                .setBinding((config, value) -> config.upscaler = value, config -> config.upscaler)
                .setImpact(OptionImpact.HIGH)
                .build())
            .add(OptionImpl.createBuilder(Quality.class, STORAGE)
                .setId(OptionIdentifier.create("amplio", "quality", Quality.class))
                .setName(TextComponent.translatable("amplio.options.quality"))
                .setTooltip(TextComponent.translatable("amplio.options.quality.tooltip"))
                .setControl(option -> new CyclingControl<>(option, Quality.class, names("amplio.quality.", Quality.values())))
                .setBinding((config, value) -> config.quality = value, config -> config.quality)
                .setEnabledPredicate(() -> Amplio.CONFIG.upscaler != UpscalerType.OFF)
                .setImpact(OptionImpact.HIGH)
                .build())
            .add(OptionImpl.createBuilder(DlssModel.class, STORAGE)
                .setId(OptionIdentifier.create("amplio", "dlss_model", DlssModel.class))
                .setName(TextComponent.translatable("amplio.options.dlssModel"))
                .setTooltip(TextComponent.translatable("amplio.options.dlssModel.tooltip"))
                .setControl(option -> new CyclingControl<>(option, DlssModel.class, names("amplio.dlssModel.", DlssModel.values())))
                .setBinding((config, value) -> config.dlssModel = value, config -> config.dlssModel)
                .setEnabledPredicate(() -> Amplio.CONFIG.upscaler == UpscalerType.DLSS)
                .setImpact(OptionImpact.MEDIUM)
                .build())
            .add(OptionImpl.createBuilder(int.class, STORAGE)
                .setId(OptionIdentifier.create("amplio", "sharpness", int.class))
                .setName(TextComponent.translatable("amplio.options.sharpness"))
                .setTooltip(TextComponent.translatable("amplio.options.sharpness.tooltip"))
                // rcas counts sharpness in stops of reduction, the slider shows it the other way round
                .setControl(option -> new SliderControl(option, 0, 100, 5, value -> TextComponent.literal(value + "%")))
                .setBinding((config, value) -> config.sharpness = (100 - value) / 50.0F, config -> 100 - Math.round(config.sharpness * 50.0F))
                .setEnabledPredicate(() -> Amplio.CONFIG.upscaler == UpscalerType.FSR1)
                .setImpact(OptionImpact.LOW)
                .build())
            .build();

        return new OptionPage(OptionIdentifier.create("amplio", "upscaling"), TextComponent.translatable("amplio.options.page"), Collections.singletonList(group));
    }

    private static TextComponent[] names(String prefix, Enum<?>[] values) {
        return Arrays.stream(values).map(value -> TextComponent.translatable(prefix + value.name().toLowerCase(Locale.ROOT))).toArray(TextComponent[]::new);
    }
}
