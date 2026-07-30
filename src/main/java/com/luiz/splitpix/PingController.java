package com.luiz.splitpix;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Health endpoint, declared permanent in addendum 35.8. */
@RestController
@RequestMapping("/api/v1")
public class PingController {

	@GetMapping("/ping")
	public Map<String, String> ping() {
		return Map.of("status", "ok");
	}

}
