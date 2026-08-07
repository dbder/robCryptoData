package nl.debo.cryptodata.predict;

/**
 * Predicts that today's direction repeats tomorrow, read from the 1-day
 * return feature (a flat day counts as "not up", matching the labeler's
 * strict comparison).
 */
public final class SameDirectionBaseline implements Predictor {

    private final int ret1Index;

    /**
     * @param ret1Index index of the 1-day return within each feature row,
     *                  from {@link Dataset#featureIndex(String)}
     */
    public SameDirectionBaseline(int ret1Index) {
        this.ret1Index = ret1Index;
    }

    @Override
    public String name() {
        return "sameDirection";
    }

    @Override
    public void fit(double[][] x, int[] y) {
    }

    @Override
    public double predictProbability(double[] features) {
        return features[ret1Index] > 0 ? 1.0 : 0.0;
    }
}
