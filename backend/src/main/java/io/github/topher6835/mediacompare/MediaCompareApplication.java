package io.github.topher6835.mediacompare;

import java.io.IOException;
import java.util.Arrays;

import io.github.topher6835.mediacompare.management.ManagementApplication;
import io.github.topher6835.mediacompare.management.CatalogChildApplication;
import io.github.topher6835.mediacompare.session.SessionCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MediaCompareApplication {

	public static void main(String[] args) throws IOException {
		if (args.length > 0 && "catalog-child".equals(args[0])) {
			try {
				CatalogChildApplication.main(Arrays.copyOfRange(args, 1, args.length));
			} catch (Exception exception) {
				throw new IllegalStateException("Managed catalog initialization or shutdown failed", exception);
			}
			return;
		}
		if (args.length > 0 && "manage".equals(args[0])) {
			ManagementApplication.main(Arrays.copyOfRange(args, 1, args.length));
			return;
		}
		if (args.length > 0 && "session".equals(args[0])) {
			SessionCommand.run(args);
			return;
		}
		SpringApplication.run(MediaCompareApplication.class, args);
	}

}
