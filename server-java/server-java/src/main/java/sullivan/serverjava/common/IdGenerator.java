package sullivan.serverjava.common;

import java.util.Locale;
import java.util.UUID;

public final class IdGenerator {
    private IdGenerator() {
    }

    public static String next() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase(Locale.ROOT);
    }
}
