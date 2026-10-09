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

@SpringBootTest(properties = {"pixelwar.board.width=20", "pixelwar.board.height=20", "pixelwar.preview.width=10", "pixelwar.preview.height=10",
        "pixelwar.players.interval-ns=1000", "pixelwar.stock.enabled=true", "pixelwar.stock.initial=100", "pixelwar.stock.capacity=1000",
        "pixelwar.conversion.enabled=true"})
@AutoConfigureMockMvc
class ApiTests {
    @Autowired MockMvc mvc;
    @Autowired Simulation simulation;
    @BeforeEach void reset() { simulation.reset(); }
    @Test void lifecycleAndStocks() throws Exception {
        mvc.perform(get("/api/simulation")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPED"))
                .andExpect(jsonPath("$.configuration.intervalNs").value(1000))
                .andExpect(jsonPath("$.configuration.conversionEnabled").value(true))
                .andExpect(jsonPath("$.players[0].eliminated").value(false))
                .andExpect(jsonPath("$.players[0].stock").value(100)).andExpect(jsonPath("$.players[0].stockCapacity").value(1000));
        mvc.perform(post("/api/simulation/pause")).andExpect(status().isConflict()).andExpect(jsonPath("$.error").exists());
        action("start", "RUNNING"); action("pause", "PAUSED"); action("resume", "RUNNING"); action("stop", "STOPPED"); action("reset", "STOPPED");
        mvc.perform(get("/api/simulation")).andExpect(jsonPath("$.attempts").value(0)).andExpect(jsonPath("$.players[0].stock").value(100));
    }
    @Test void scoresMetricsPreviewAndStaticPage() throws Exception {
        mvc.perform(get("/api/simulation/scores")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$[0].cells").value(0));
        mvc.perform(get("/api/metrics")).andExpect(status().isOk()).andExpect(jsonPath("$.totalCells").value(400))
                .andExpect(jsonPath("$.cellsByColor.EMPTY").value(400)).andExpect(jsonPath("$.pixelsConsumed").value(0))
                .andExpect(jsonPath("$.conversions").value(0)).andExpect(jsonPath("$.conversionDurationNanos.minimumNanos").value(0))
                .andExpect(jsonPath("$.actions").value(0)).andExpect(jsonPath("$.players[0].conversionsReceived").value(0));
        mvc.perform(get("/api/board/preview")).andExpect(status().isOk()).andExpect(jsonPath("$.width").value(10)).andExpect(jsonPath("$.cells[0][0]").value("EMPTY"));
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(content().string("OK"));
    }
    private void action(String command, String state) throws Exception {
        mvc.perform(post("/api/simulation/" + command)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value(state));
    }
}
