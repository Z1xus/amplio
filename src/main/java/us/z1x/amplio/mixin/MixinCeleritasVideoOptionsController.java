package us.z1x.amplio.mixin;

import org.embeddedt.embeddium.impl.gui.CeleritasVideoOptionsController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.taumc.celeritas.api.options.structure.OptionPage;
import us.z1x.amplio.gui.AmplioOptionPage;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = CeleritasVideoOptionsController.class, remap = false)
public class MixinCeleritasVideoOptionsController {
	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
	private static List<OptionPage> amplio$addOptionPage(List<OptionPage> pages) {
		List<OptionPage> withAmplio = new ArrayList<>(pages);
		withAmplio.add(AmplioOptionPage.create());
		return withAmplio;
	}
}
