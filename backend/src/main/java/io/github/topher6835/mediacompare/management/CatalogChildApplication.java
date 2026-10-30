package io.github.topher6835.mediacompare.management;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import io.github.topher6835.mediacompare.MediaCompareApplication;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/** Private pipe-controlled catalog entry point; direct Spring startup is unchanged. */
public final class CatalogChildApplication {
    private CatalogChildApplication() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Catalog child requires a launch token and Session path");
        String token = args[0];
        String sessionPath = CatalogChildCommand.sessionPath(args[1]);
        var stop = new CountDownLatch(1);
        Thread control = new Thread(() -> awaitStop(token, stop), "media-compare-catalog-control");
        control.setDaemon(true);
        control.start();
        System.out.println("MC_CATALOG " + token + " INITIALIZING");
        boolean initialized = false;
        var application = new SpringApplication(MediaCompareApplication.class);
        application.addInitializers(context -> context.getBeanFactory().registerSingleton(
                "managedCatalogSecurity", ManagedCatalogSecurity.registration(token)));
        try (var context = application.run(
                "--media-compare.session-root=" + sessionPath,
                "--media-compare.test-catalog-override=false",
                "--server.address=127.0.0.1", "--server.port=0", "--server.ssl.enabled=false",
                "--spring.main.lazy-initialization=false", "--spring.main.web-application-type=servlet")) {
            // run() returns after context initialization, migrations, recovery, runners and readiness events.
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            initialized = true;
            if (stop.getCount() != 0) System.out.println("MC_CATALOG " + token + " READY " + port);
            stop.await();
            // Context close runs the existing cleanup. Do not force JVM exit if workers remain alive.
        } catch (Exception exception) {
            var failure = initialized ? CatalogStatus.Failure.SHUTDOWN_FAILED : failureCode(exception);
            System.out.println("MC_CATALOG " + token + " FAILED " + failure);
            throw exception;
        }
    }

    private static void awaitStop(String token, CountDownLatch stop) {
        try {
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                if (line.equals("STOP " + token)) break;
            }
        } catch (java.io.IOException ignored) {
            // Broken control pipe is equivalent to losing the parent.
        } finally {
            stop.countDown(); // EOF also requests cleanup after normal or abnormal parent exit.
        }
    }

    private static CatalogStatus.Failure failureCode(Exception failure) {
        Throwable cause = failure;
        boolean ownershipFailure = false;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof BeanCreationException bean) {
                if ("catalogPaths".equals(bean.getBeanName())) return CatalogStatus.Failure.INVALID_SESSION;
                if ("catalogOwnership".equals(bean.getBeanName())) ownershipFailure = true;
            }
        }
        return ownershipFailure ? CatalogStatus.Failure.OWNERSHIP_FAILED : CatalogStatus.Failure.INITIALIZATION_FAILED;
    }
}
