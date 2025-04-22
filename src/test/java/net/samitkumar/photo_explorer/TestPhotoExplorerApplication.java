package net.samitkumar.photo_explorer;

import org.springframework.boot.SpringApplication;

public class TestPhotoExplorerApplication {

	public static void main(String[] args) {
		SpringApplication.from(PhotoExplorerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
