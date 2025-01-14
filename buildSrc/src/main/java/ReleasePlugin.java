import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.authentication.http.BasicAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Takes care of adding tasks and configurations to build developer distributions and releases.
 */
public class ReleasePlugin implements Plugin<Project> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReleasePlugin.class);

    public static final String SNAPSHOT_REPOSITORY_URL = "https://repo.gradle.org/gradle/ext-snapshots-local";
    private static final String RELEASES_REPOSITORY_URL = "https://dl.bintray.com/adammurdoch/maven";

    private static final DateTimeFormatter SNAPSHOT_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssZ", Locale.US).withZone(ZoneOffset.UTC);
    private static final String BUILD_RECEIPT_NAME = "build-receipt.properties";
    private static final String BUILD_TIMESTAMP_PROPERTY = "buildTimestamp";

    @Override
    public void apply(Project project) {
        VersionDetails.BuildType buildType = determineBuildType(project);
        VersionDetails versions = project.getExtensions().create("versions", VersionDetails.class, buildType);
        String buildTimestamp = determineBuildTimestamp(project);
        if (buildType == VersionDetails.BuildType.Snapshot) {
            writeBuildTimestamp(buildTimestamp, project);
        }

        project.allprojects(subproject -> {
            subproject.getPlugins().apply(UploadPlugin.class);
            subproject.getPlugins().apply(PublishSnapshotPlugin.class);
            subproject.setVersion(new VersionCalculator(versions, buildType, buildTimestamp));

            // Use authenticated snapshot/bintray repo while building a test distribution during snapshot/release
            final BintrayCredentials credentials = subproject.getExtensions().getByType(BintrayCredentials.class);

            if (versions.isUseRepo()) {
                credentials.assertPresent();
                String repositoryUrl = buildType == VersionDetails.BuildType.Snapshot
                        ? SNAPSHOT_REPOSITORY_URL
                        : RELEASES_REPOSITORY_URL;
                subproject.getRepositories().maven(repo -> {
                    repo.setUrl(repositoryUrl);
                    repo.getCredentials().setUsername(credentials.getUserName());
                    repo.getCredentials().setPassword(credentials.getApiKey());
                    repo.getAuthentication().create("basic", BasicAuthentication.class);
                });
            }

            addUploadLifecycleTasks(subproject, buildType);
        });
    }

    private void addUploadLifecycleTasks(Project project, VersionDetails.BuildType buildType) {
        Task uploadMainLifecycle = project.getTasks().maybeCreate("uploadMain");
        uploadMainLifecycle.setGroup("Upload");
        uploadMainLifecycle.setDescription("Upload Main publication");

        Task uploadJniLifecycle = project.getTasks().maybeCreate("uploadJni");
        uploadJniLifecycle.setGroup("Upload");
        uploadJniLifecycle.setDescription("Upload all JNI publications");

        project.getExtensions().configure(
                PublishingExtension.class,
                extension -> extension.getPublications().withType(MavenPublication.class, publication -> {
                    String uploadTaskName = buildType == VersionDetails.BuildType.Snapshot
                            ? PublishSnapshotPlugin.uploadTaskName(publication)
                            : UploadPlugin.uploadTaskName(publication);
                    TaskProvider<Task> uploadTask = project.getTasks().named(uploadTaskName);
                    if (BasePublishPlugin.isMainPublication(publication)) {
                        uploadMainLifecycle.dependsOn(uploadTask);
                    } else {
                        uploadJniLifecycle.dependsOn(uploadTask);
                    }
                }));
    }

    private void writeBuildTimestamp(String buildTimestamp, Project project) {
        File buildReceiptFile = project.getRootProject().file(BUILD_RECEIPT_NAME);
        try (OutputStream outputStream = Files.newOutputStream(buildReceiptFile.toPath())) {
            Properties properties = new Properties();
            properties.setProperty(BUILD_TIMESTAMP_PROPERTY, buildTimestamp);
            properties.store(outputStream, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String determineBuildTimestamp(Project project) {
        File buildReceipt = new File(project.getRootProject().file("incoming-distributions"), BUILD_RECEIPT_NAME);
        if (project.hasProperty("ignoreIncomingBuildReceipt") || !buildReceipt.isFile()) {
            return ZonedDateTime.now().format(SNAPSHOT_TIMESTAMP_FORMATTER);
        }
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(buildReceipt.toPath())) {
            properties.load(inputStream);
            String buildTimestamp = properties.getProperty(BUILD_TIMESTAMP_PROPERTY);
            LOGGER.warn("Using build timestamp from incoming build receipt: {}", buildTimestamp);
            return properties.getProperty("buildTimestamp");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private VersionDetails.BuildType determineBuildType(Project project) {
        boolean snapshot = project.hasProperty("snapshot");
        boolean release = project.hasProperty("release");
        boolean milestone = project.hasProperty("milestone");

        Set<VersionDetails.BuildType> enabledBuildTypes = EnumSet.noneOf(VersionDetails.BuildType.class);
        if (release) {
            enabledBuildTypes.add(VersionDetails.BuildType.Release);
        }
        if (milestone) {
            enabledBuildTypes.add(VersionDetails.BuildType.Milestone);
        }
        if (snapshot) {
            enabledBuildTypes.add(VersionDetails.BuildType.Snapshot);
        }
        if (enabledBuildTypes.size() > 1) {
            throw new UnsupportedOperationException(
                    "Cannot build " +
                            enabledBuildTypes.stream()
                                    .map(Object::toString)
                                    .collect(Collectors.joining(" and ")) +
                            " in same build.");
        }
        return enabledBuildTypes.stream().findFirst().orElse(VersionDetails.BuildType.Dev);
    }

    private static class VersionCalculator {
        private final VersionDetails release;
        private final VersionDetails.BuildType buildType;
        private final String buildTimestamp;
        private String version;

        VersionCalculator(VersionDetails release, VersionDetails.BuildType buildType, String buildTimestamp) {
            this.release = release;
            this.buildType = buildType;
            this.buildTimestamp = buildTimestamp;
        }

        @Override
        public String toString() {
            if (version == null) {
                String nextVersion = release.getNextVersion();
                if (nextVersion == null) {
                    throw new UnsupportedOperationException("Next version not specified.");
                }
                if (buildType == VersionDetails.BuildType.Release) {
                    version = nextVersion;
                } else if (buildType == VersionDetails.BuildType.Milestone) {
                    if (release.getNextSnapshot() == null) {
                        throw new UnsupportedOperationException("Next milestone not specified.");
                    }
                    version = nextVersion + "-milestone-" + release.getNextSnapshot();
                } else if (buildType == VersionDetails.BuildType.Snapshot) {
                    version = nextVersion + "-snapshot-" + buildTimestamp;
                } else {
                    version = nextVersion + "-dev";
                }
            }
            return version;
        }
    }
}
