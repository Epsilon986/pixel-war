package org.pixelwar.pixelwar;

import org.pixelwar.pixelwar.domain.Simulation;
import org.pixelwar.pixelwar.domain.Stock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PixelWarConfiguration {
    @Bean(destroyMethod = "close")
    Simulation simulation(@Value("${pixelwar.board.width:4000}") int width,
                          @Value("${pixelwar.board.height:4000}") int height,
                          @Value("${pixelwar.players.interval-ns:1000}") long intervalNs,
                          @Value("${pixelwar.preview.width:200}") int previewWidth,
                          @Value("${pixelwar.preview.height:200}") int previewHeight,
                          @Value("${pixelwar.stock.enabled:true}") String stockEnabled,
                          @Value("${pixelwar.stock.initial:100}") int initial,
                          @Value("${pixelwar.stock.capacity:1000}") int capacity,
                          @Value("${pixelwar.stock.refill-amount:100}") long refillAmount,
                          @Value("${pixelwar.stock.refill-interval-ns:1000000}") long refillIntervalNs,
                          @Value("${pixelwar.players.max-pixels-per-action:10}") int maxPixels,
                          @Value("${pixelwar.conversion.enabled:true}") String conversionEnabled,
                          @Value("${pixelwar.players.strategy:frontier}") String strategy,
                          @Value("${pixelwar.players.exploration-probability:0.10}") double explorationProbability,
                          @Value("${pixelwar.players.frontier-sample-size:10}") int frontierSampleSize) {
        if (!"true".equalsIgnoreCase(conversionEnabled) && !"false".equalsIgnoreCase(conversionEnabled)) {
            throw new IllegalArgumentException("pixelwar.conversion.enabled must be true or false");
        }
        if (!"true".equalsIgnoreCase(stockEnabled) && !"false".equalsIgnoreCase(stockEnabled)) {
            throw new IllegalArgumentException("pixelwar.stock.enabled must be true or false");
        }
        // Validate everything before allocating a potentially very large board.
        var stock = new Stock.Configuration(initial, capacity, refillAmount, refillIntervalNs, Boolean.parseBoolean(stockEnabled));
        var configuration = new Simulation.Configuration(width, height, intervalNs, previewWidth, previewHeight,
                stock, maxPixels, Boolean.parseBoolean(conversionEnabled), strategy, explorationProbability, frontierSampleSize);
        return new Simulation(configuration);
    }
}
