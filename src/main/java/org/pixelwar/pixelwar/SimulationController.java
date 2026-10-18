package org.pixelwar.pixelwar;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.pixelwar.pixelwar.domain.Simulation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api")
public class SimulationController {
    private final Simulation simulation;
    private final HttpPerformanceFilter http;
    public SimulationController(Simulation simulation, HttpPerformanceFilter http) { this.simulation = simulation; this.http = http; }
    public record TrialRequest(long actions, long seed) {}
    public record PerformanceResponse(Simulation.Performance engine, HttpPerformanceFilter.Snapshot http) {}
    @GetMapping("/simulation") public Simulation.Status status() { return simulation.status(); }
    @PostMapping("/simulation/start") public Simulation.Status start() { return simulation.start(); }
    @PostMapping("/simulation/pause") public Simulation.Status pause() { return simulation.pause(); }
    @PostMapping("/simulation/resume") public Simulation.Status resume() { return simulation.resume(); }
    @PostMapping("/simulation/stop") public Simulation.Status stop() { return simulation.stop(); }
    @PostMapping("/simulation/reset") public Simulation.Status reset(HttpServletRequest request) { var state = simulation.reset(); http.resetIncluding(request); return state; }
    @PostMapping("/simulation/trial") public Simulation.Status trial(@RequestBody TrialRequest trial, HttpServletRequest request) {
        var status = simulation.startTrial(trial.actions(), trial.seed());
        http.resetIncluding(request);
        return status;
    }
    @GetMapping("/performance") public PerformanceResponse performance() { return new PerformanceResponse(simulation.performance(), http.snapshot()); }
    @GetMapping("/simulation/scores") public List<Simulation.Score> scores() { return simulation.scores(); }
    @GetMapping("/metrics") public Simulation.Metrics metrics() { return simulation.metrics(); }
    @GetMapping("/board/preview") public Simulation.Preview preview() { return simulation.preview(); }
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> invalidTransition(IllegalStateException exception) { return Map.of("error", exception.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidArgument(IllegalArgumentException exception) { return Map.of("error", exception.getMessage()); }
}
