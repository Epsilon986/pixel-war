package org.pixelwar.pixelwar;

import java.util.List;
import java.util.Map;
import org.pixelwar.pixelwar.domain.Simulation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SimulationController {
    private final Simulation simulation;
    public SimulationController(Simulation simulation) { this.simulation = simulation; }
    @GetMapping("/simulation") public Simulation.Status status() { return simulation.status(); }
    @PostMapping("/simulation/start") public Simulation.Status start() { return simulation.start(); }
    @PostMapping("/simulation/pause") public Simulation.Status pause() { return simulation.pause(); }
    @PostMapping("/simulation/resume") public Simulation.Status resume() { return simulation.resume(); }
    @PostMapping("/simulation/stop") public Simulation.Status stop() { return simulation.stop(); }
    @PostMapping("/simulation/reset") public Simulation.Status reset() { return simulation.reset(); }
    @GetMapping("/simulation/scores") public List<Simulation.Score> scores() { return simulation.scores(); }
    @GetMapping("/metrics") public Simulation.Metrics metrics() { return simulation.metrics(); }
    @GetMapping("/board/preview") public Simulation.Preview preview() { return simulation.preview(); }
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> invalidTransition(IllegalStateException exception) { return Map.of("error", exception.getMessage()); }
}
