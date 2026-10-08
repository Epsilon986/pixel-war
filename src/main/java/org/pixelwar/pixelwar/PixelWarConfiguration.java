package org.pixelwar.pixelwar;

import org.pixelwar.pixelwar.domain.Board;
import org.pixelwar.pixelwar.domain.Simulation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PixelWarConfiguration {
    @Bean(destroyMethod = "close")
    Simulation simulation(@Value("${pixelwar.board.width:4000}") int width,
                          @Value("${pixelwar.board.height:4000}") int height,
                          @Value("${pixelwar.players.interval-ms:10}") long interval,
                          @Value("${pixelwar.preview.width:200}") int previewWidth,
                          @Value("${pixelwar.preview.height:200}") int previewHeight) {
        if (interval <= 0) throw new IllegalArgumentException("pixelwar.players.interval-ms must be positive");
        if (previewWidth <= 0 || previewHeight <= 0) throw new IllegalArgumentException("pixelwar.preview dimensions must be positive");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("pixelwar.board dimensions must be positive");
        return new Simulation(new Board(width, height), interval, previewWidth, previewHeight);
    }
}
