package com.luiz.splitpix;

import org.springframework.boot.SpringApplication;

public class TestSplitpixApplication {

	public static void main(String[] args) {
		SpringApplication.from(SplitpixApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
