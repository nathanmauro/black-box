package dev.nathan.sbaagentic.recording.internal.application;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * The values of every setting this process was started with whose name is a credential name:
 * environment variables, system properties, command-line arguments, configuration files and
 * imported files alike. Reading them by name keeps this module independent of the modules that own
 * the settings, and covers a credential a later module adds without a change here.
 */
final class ConfiguredSecretValues {

    private ConfiguredSecretValues() {}

    static List<String> collect(Environment environment, Predicate<String> credentialName) {
        List<String> values = new ArrayList<>();
        if (!(environment instanceof ConfigurableEnvironment configurable)) {

            return values;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!credentialName.test(name)) {
                    continue;
                }
                // As the source holds it, and as the application reads it once placeholders resolve.
                add(values, () -> enumerable.getProperty(name));
                add(values, () -> environment.getProperty(name));
            }
        }

        return values;
    }

    private static void add(List<String> values, Supplier<Object> read) {
        try {
            Object value = read.get();
            if (value != null) {
                values.add(value.toString());
            }
        } catch (RuntimeException ex) {
            // An unresolvable placeholder or a failing source holds no value to mask.
        }
    }
}
