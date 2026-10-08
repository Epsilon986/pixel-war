package org.pixelwar.pixelwar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"pixelwar.board.width=20", "pixelwar.board.height=20"})
class PixelWarApplicationTests {

    @Test
    void contextLoads() {
    }

}
