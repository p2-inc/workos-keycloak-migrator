package io.phasetwo.migration.it;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Future;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Keycloak container with the Phase Two providers installed and file-based dev storage. Use {@link
 * #withProvidersDir(Path)} to mount our extension jars alongside them.
 *
 * <p>The image is built from {@code docker/Dockerfile}, which layers the provider jars out of the
 * newest published {@code phasetwo-keycloak} image onto the Keycloak version we target. See that
 * file for why we don't just pull a Phase Two tag directly.
 */
public class PhaseTwoKeycloakContainer extends GenericContainer<PhaseTwoKeycloakContainer> {

  public PhaseTwoKeycloakContainer() {
    this(phaseTwoImage());
  }

  public PhaseTwoKeycloakContainer(Future<String> image) {
    super(image);
    withExposedPorts(8080, 9000);
    withEnv("KC_DB", "dev-file");
    withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
    withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin");
    withEnv("KC_HOSTNAME_STRICT", "false");
    withEnv("KC_HOSTNAME_STRICT_HTTPS", "false");
    withEnv("KC_HTTP_ENABLED", "true");
    withEnv("KC_HEALTH_ENABLED", "true");
    // start-dev keeps boot quick and disables SSL on most paths; we still need
    // sslRequired=NONE on individual realms which the bootstrap step handles.
    withCommand("start-dev", "--features=organization");
    // KC 26 exposes /health/ready on the management port (9000), not the user-facing 8080.
    waitingFor(
        Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));
    withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("kc-container")));
  }

  /**
   * Build (or reuse) the Keycloak + Phase Two providers image. The tag is derived from the two
   * source image tags so a change to either produces a fresh build while repeat runs hit the Docker
   * layer cache. {@code deleteOnExit=false} keeps the image around between {@code mvn -Pit verify}
   * invocations.
   */
  private static Future<String> phaseTwoImage() {
    Path dockerfile =
        Path.of(System.getProperty("wkm.dockerfile", "../docker/Dockerfile"))
            .toAbsolutePath()
            .normalize();
    if (!Files.isRegularFile(dockerfile)) {
      throw new IllegalStateException("Dockerfile not found: " + dockerfile);
    }
    String keycloakImage = System.getProperty("wkm.keycloak.image", "quay.io/keycloak/keycloak");
    String keycloakTag = System.getProperty("wkm.keycloak.tag", "26.7.3");
    String phasetwoImage =
        System.getProperty("wkm.phasetwo.image", "quay.io/phasetwo/phasetwo-keycloak");
    String phasetwoTag = System.getProperty("wkm.phasetwo.tag", "26.6.6");
    return new ImageFromDockerfile(
            "workos-keycloak-migrator/keycloak:" + keycloakTag + "-pt" + phasetwoTag, false)
        .withDockerfile(dockerfile)
        .withBuildArg("KEYCLOAK_IMAGE", keycloakImage)
        .withBuildArg("KEYCLOAK_TAG", keycloakTag)
        .withBuildArg("PHASETWO_IMAGE", phasetwoImage)
        .withBuildArg("PHASETWO_TAG", phasetwoTag);
  }

  /**
   * Copy every JAR in {@code dir} into {@code /opt/keycloak/providers/} on the container
   * <em>alongside</em> the image's pre-installed providers (a directory bind-mount would shadow
   * them and Keycloak would fail to start).
   */
  public PhaseTwoKeycloakContainer withProvidersDir(Path dir) {
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("providers dir does not exist: " + dir.toAbsolutePath());
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
      for (Path jar : stream) {
        addFileSystemBind(
            jar.toAbsolutePath().toString(),
            "/opt/keycloak/providers/" + jar.getFileName().toString(),
            BindMode.READ_ONLY);
      }
    } catch (IOException e) {
      throw new RuntimeException("failed to enumerate providers dir " + dir, e);
    }
    return self();
  }

  public String baseUrl() {
    return "http://" + getHost() + ":" + getMappedPort(8080);
  }

  /**
   * Run {@code kcadm.sh} commands inside the container — used to disable {@code sslRequired} on the
   * master realm before the host can authenticate over plain HTTP.
   */
  public void disableMasterSsl() {
    try {
      org.testcontainers.containers.Container.ExecResult login =
          execInContainer(
              "/opt/keycloak/bin/kcadm.sh",
              "config",
              "credentials",
              "--server",
              "http://localhost:8080",
              "--realm",
              "master",
              "--user",
              "admin",
              "--password",
              "admin");
      if (login.getExitCode() != 0) {
        throw new IllegalStateException("kcadm login failed: " + login.getStderr());
      }
      org.testcontainers.containers.Container.ExecResult update =
          execInContainer(
              "/opt/keycloak/bin/kcadm.sh", "update", "realms/master", "-s", "sslRequired=NONE");
      if (update.getExitCode() != 0) {
        throw new IllegalStateException("kcadm update failed: " + update.getStderr());
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
