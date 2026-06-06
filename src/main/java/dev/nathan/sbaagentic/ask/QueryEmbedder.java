package dev.nathan.sbaagentic.ask;

@FunctionalInterface
public interface QueryEmbedder {

    float[] embed(String query);

    default AskComponentStatus status() {
        return AskComponentStatus.available("enabled");
    }

    static QueryEmbedder unavailable(String detail) {
        return new QueryEmbedder() {
            @Override
            public float[] embed(String query) {
                throw new AskDependencyUnavailable(detail);
            }

            @Override
            public AskComponentStatus status() {
                return AskComponentStatus.unavailable(detail);
            }
        };
    }
}
