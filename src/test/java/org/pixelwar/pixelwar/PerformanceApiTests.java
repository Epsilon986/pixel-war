package org.pixelwar.pixelwar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pixelwar.pixelwar.domain.Simulation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"pixelwar.board.width=20", "pixelwar.board.height=20", "pixelwar.stock.enabled=false"})
@AutoConfigureMockMvc
class PerformanceApiTests {
    @Autowired MockMvc mvc;
    @Autowired Simulation simulation;
    @BeforeEach void reset() throws Exception { mvc.perform(post("/api/simulation/reset")).andExpect(status().isOk()); }
    @Test void lightweightMetricsExposeOnlyMeasurementsAndHttpWindows() throws Exception {
        mvc.perform(get("/api/performance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.engine.measurements.actions").value(0))
                .andExpect(jsonPath("$.engine.measurements.allocatedBytesPerAction").isEmpty())
                .andExpect(jsonPath("$.http.windowCapacity").value(512))
                .andExpect(jsonPath("$.http.commands.samples").value(1))
                .andExpect(jsonPath("$.engine.measurements.players").doesNotExist());
        mvc.perform(get("/api/performance")).andExpect(jsonPath("$.http.reads.samples").value(1))
                .andExpect(jsonPath("$.http.reads.p95Nanos").isNumber());
    }
    @Test void trialApiValidatesAndStopsAtExactCount() throws Exception {
        mvc.perform(post("/api/simulation/trial").contentType("application/json").content("{\"actions\":0,\"seed\":42}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/simulation/trial").contentType("application/json").content("{\"actions\":17,\"seed\":42}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            while (!simulation.performance().completed()) Thread.sleep(2);
        });
        mvc.perform(get("/api/performance")).andExpect(jsonPath("$.engine.completed").value(true))
                .andExpect(jsonPath("$.engine.measurements.actions").value(17))
                .andExpect(jsonPath("$.engine.seed").value(42))
                .andExpect(jsonPath("$.engine.measurements.cpuNanos").isNumber())
                .andExpect(jsonPath("$.engine.measurements.allocatedBytesPerAction").isNumber());
        mvc.perform(post("/api/simulation/resume")).andExpect(status().isConflict());
    }
}
