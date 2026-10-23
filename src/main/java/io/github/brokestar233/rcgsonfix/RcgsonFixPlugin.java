package io.github.brokestar233.rcgsonfix;

import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;
import org.jetbrains.annotations.NotNull;

public class RcgsonFixPlugin implements RfbPlugin {
    @Override
    public @NotNull RfbClassTransformer[] makeTransformers() {
        return new RfbClassTransformer[] { new GsonLegacyLeniencyTransformer() };
    }
}
