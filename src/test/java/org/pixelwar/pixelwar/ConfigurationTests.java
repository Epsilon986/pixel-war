package org.pixelwar.pixelwar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTests {
    @Test void invalidConfigurationPreventsStartup() {
        for (String property : new String[]{"pixelwar.board.width=0", "pixelwar.board.height=-1", "pixelwar.players.interval-ms=0", "pixelwar.preview.width=0", "pixelwar.preview.height=-2"}) {
            new ApplicationContextRunner().withUserConfiguration(PixelWarConfiguration.class)
                    .withPropertyValues("pixelwar.board.width=2", "pixelwar.board.height=2", property)
                    .run(context -> {
                        assertNotNull(context.getStartupFailure());
                        Throwable cause = context.getStartupFailure();
                        while (cause.getCause() != null) cause = cause.getCause();
                        assertInstanceOf(IllegalArgumentException.class, cause);
                        assertTrue(cause.getMessage().contains("pixelwar."));
                    });
        }
    }
}
