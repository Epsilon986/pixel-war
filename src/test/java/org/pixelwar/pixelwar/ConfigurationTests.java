package org.pixelwar.pixelwar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTests {
    @Test void stockCanBeDisabledByProperty() {
        new ApplicationContextRunner().withUserConfiguration(PixelWarConfiguration.class)
                .withPropertyValues("pixelwar.board.width=2", "pixelwar.board.height=2", "pixelwar.stock.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBean(org.pixelwar.pixelwar.domain.Simulation.class).status().configuration().stock().enabled());
                });
    }
    @Test void invalidConfigurationPreventsStartup() {
        for (var property : new String[]{"pixelwar.board.width=0", "pixelwar.board.height=-1", "pixelwar.players.interval-ns=0",
                "pixelwar.preview.width=0", "pixelwar.stock.initial=-1", "pixelwar.stock.initial=1001", "pixelwar.stock.capacity=0",
                "pixelwar.stock.refill-amount=0", "pixelwar.stock.refill-interval-ns=0", "pixelwar.players.max-pixels-per-action=0",
                "pixelwar.players.max-pixels-per-action=1001", "pixelwar.conversion.enabled=perhaps", "pixelwar.stock.enabled=perhaps",
                "pixelwar.stock.capacity=99999999999999999999", "pixelwar.stock.refill-amount=99999999999999999999"}) {
            new ApplicationContextRunner().withUserConfiguration(PixelWarConfiguration.class)
                    .withPropertyValues("pixelwar.board.width=2", "pixelwar.board.height=2", property)
                    .run(context -> assertNotNull(context.getStartupFailure(), property));
        }
    }
}
