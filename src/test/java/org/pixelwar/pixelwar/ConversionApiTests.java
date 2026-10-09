package org.pixelwar.pixelwar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.pixelwar.pixelwar.domain.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"pixelwar.board.width=3", "pixelwar.board.height=3"})
@AutoConfigureMockMvc
@Import(ConversionApiTests.DeterministicConfiguration.class)
class ConversionApiTests {
    @TestConfiguration
    static class DeterministicConfiguration {
        final List<Runnable> tasks = new ArrayList<>();
        @Bean @Primary Simulation deterministicSimulation() {
            var board = new Board(3, 3);
            board.place(1, 0, CellState.RED); board.place(1, 2, CellState.RED);
            board.place(0, 1, CellState.RED); board.place(2, 1, CellState.RED);
            var executor = mock(ScheduledExecutorService.class);
            when(executor.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS)))
                    .thenAnswer(call -> { tasks.add(call.getArgument(0)); return mock(ScheduledFuture.class); });
            var config = new Simulation.Configuration(3, 3, 1000, 3, 3, new Stock.Configuration(1, 10, 1, 1_000_000), 1, true);
            return new Simulation(board, config, (b, p) -> new PositionStrategy.Position(0, 0), () -> 0L, executor);
        }
    }
    @Autowired MockMvc mvc;
    @Autowired DeterministicConfiguration deterministic;

    @Test void apiReportsConvertedPreviewScoreAndBeneficiaryWithoutStockCharge() throws Exception {
        mvc.perform(post("/api/simulation/start")).andExpect(status().isOk());
        deterministic.tasks.get(0).run(); // A full round: red places at (0,0), then the center converts.
        mvc.perform(post("/api/simulation/pause")).andExpect(status().isOk());
        mvc.perform(get("/api/board/preview")).andExpect(jsonPath("$.cells[1][1]").value("RED"));
        mvc.perform(get("/api/simulation/scores")).andExpect(jsonPath("$[0].cells").value(6));
        mvc.perform(get("/api/metrics")).andExpect(jsonPath("$.conversions").value(1))
                .andExpect(jsonPath("$.players[0].conversionsReceived").value(1))
                .andExpect(jsonPath("$.players[0].pixelsConsumed").value(1))
                .andExpect(jsonPath("$.players[1].pixelsConsumed").value(0))
                .andExpect(jsonPath("$.boardChanges").value(2));
        mvc.perform(post("/api/simulation/reset")).andExpect(jsonPath("$.players[1].stock").value(1));
        mvc.perform(get("/api/metrics")).andExpect(jsonPath("$.conversions").value(0)).andExpect(jsonPath("$.cellsByColor.EMPTY").value(9));
    }
}
