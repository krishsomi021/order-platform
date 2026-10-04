package com.krishna.order_platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;


@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderPlatformApplicationTests {

	@Test
	void contextLoads() {
	}

}
