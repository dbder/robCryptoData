package nl.debo.cryptodata.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fixed-band normalization: maps {@code -band..+band} linearly onto 0..1 and
 * clamps outside it. Fully interpretable but blind to how volatile the signal
 * actually is, so the band must fit the series it is applied to: around 0.10
 * for an MA distance ratio, an order of magnitude smaller (~0.02) for a
 * price-scaled MACD histogram.
 */
public final class ClampNormalizer implements SignalNormalizer {

    private final double band;

    public ClampNormalizer(double band) {
        this.band = band;
    }

    @Override
    public List<Double> normalize(List<Double> values) {

        var result = new ArrayList<Double>(
                Collections.nCopies(values.size(), Double.NaN)
        );

        for (int i = 0; i < values.size(); i++) {

            double current = values.get(i);

            if (Double.isNaN(current)) {
                continue;
            }

            double scaled = (current + band) / (2 * band);

            result.set(i, Math.max(0.0, Math.min(1.0, scaled)));
        }

        return result;
    }

    @Override
    public String name() {
        return "clamp(±" + band + ")";
    }
}
