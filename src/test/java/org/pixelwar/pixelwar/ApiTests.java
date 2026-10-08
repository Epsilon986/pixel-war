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

@SpringBootTest(properties = {"pixelwar.board.width=20", "pixelwar.board.height=20", "pixelwar.preview.width=10", "pixelwar.preview.height=10"})
@AutoConfigureMockMvc
class ApiTests {
    @Autowired MockMvc mvc;
    @Autowired Simulation simulation;
    @BeforeEach void reset() { simulation.reset(); }
    @Test void lifecycleEndpoints() throws Exception {
        mvc.perform(get("/api/simulation")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPED"));
        mvc.perform(post("/api/simulation/pause")).andExpect(status().isConflict()).andExpect(jsonPath("$.error").exists());
        action("start", "RUNNING");
        action("pause", "PAUSED");
        action("resume", "RUNNING");
        action("stop", "STOPPED");
        action("reset", "STOPPED");
        mvc.perform(get("/api/simulation")).andExpect(jsonPath("$.attempts").value(0)).andExpect(jsonPath("$.configuration.width").value(20));
    }
    @Test void scoresMetricsAndPreview() throws Exception {
        mvc.perform(get("/api/simulation/scores")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$[0].cells").value(0));
        mvc.perform(get("/api/metrics")).andExpect(status().isOk()).andExpect(jsonPath("$.totalCells").value(400))
                .andExpect(jsonPath("$.cellsByColor.EMPTY").value(400)).andExpect(jsonPath("$.jvm.heapUsedBytes").isNumber());
        mvc.perform(get("/api/board/preview")).andExpect(status().isOk()).andExpect(jsonPath("$.width").value(10))
                .andExpect(jsonPath("$.cells[0][0]").value("EMPTY"));
        mvc.perform(get("/")).andExpect(status().isOk());
    }
    private void action(String command, String state) throws Exception {
        mvc.perform(post("/api/simulation/" + command)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value(state));
    }
}
