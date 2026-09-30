package io.github.topher6835.mediacompare;

import java.io.IOException;

import io.github.topher6835.mediacompare.session.SessionCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MediaCompareApplication {

	public static void main(String[] args) throws IOException {
		if (args.length > 0 && "session".equals(args[0])) {
			SessionCommand.run(args);
			return;
		}
		SpringApplication.run(MediaCompareApplication.class, args);
	}

}
