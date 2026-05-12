package ie.bustracker.app.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ie.bustracker.app.services.GtfsStaticService;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ensures the static GTFS CSV files exist on the local filesystem before
 * {@link GtfsStaticService} tries to read them.
 *
 * On startup, checks {@code bus-tracker.gtfs.dir} for the required CSVs
 * ({@code stop_times.txt}, {@code trips.txt}, {@code routes.txt},
 * {@code calendar.txt}, {@code calendar_dates.txt}). If any are missing, it
 * downloads the TFI static GTFS zip from {@code bus-tracker.gtfs.zip-url} and
 * extracts the entries into that directory. If all required files are already
 * present, it does nothing — so local dev (where the files live in
 * {@code src/main/resources/gtfs/}) never pays the download cost.
 *
 * This bean exists because the GTFS data is too large (~500 MB) to bundle
 * inside the deployable jar or Docker image. In production the directory is
 * a persistent volume mounted into the container, which starts out empty;
 * this class is what populates it on first boot.
 *
 * {@link ie.bustracker.app.services.GtfsStaticService} uses {@code @DependsOn("gtfsBootstrap")} to
 * guarantee this runs first.
 */
@Component
public class GtfsBootstrap {
    private static final Logger log = LoggerFactory.getLogger(GtfsBootstrap.class);

    // The CSVs GtfsStaticService actually reads. If any one is missing, we re-download the whole zip.
    private static final List<String> REQUIRED = List.of(
            "stop_times.txt", "trips.txt", "routes.txt", "calendar.txt", "calendar_dates.txt"
    );

    // Where the extracted CSVs live (local: src/main/resources/gtfs; Fly: /data/gtfs).
    @Value("${bus-tracker.gtfs.dir}")
    private String gtfsDirStr;

    // Public TFI zip URL containing the static GTFS feed.
    @Value("${bus-tracker.gtfs.zip-url}")
    private String zipUrl;

    @PostConstruct
    public void ensureGtfsData() throws Exception {
        Path dir = Path.of(gtfsDirStr);

        // Fast path: directory already populated, nothing to do.
        if (allRequiredFilesPresent(dir)) {
            log.info("GTFS data already present at {}, skipping download", dir.toAbsolutePath());
            return;
        }

        log.info("GTFS data missing at {}, downloading from {}", dir.toAbsolutePath(), zipUrl);
        Files.createDirectories(dir);

        // Stage the download next to the target dir, not inside it, so a half-finished
        // file can't be mistaken for a real GTFS entry.
        Path tmpZip = dir.resolveSibling(dir.getFileName().toString() + ".zip.tmp");
        try {
            download(zipUrl, tmpZip);
            extract(tmpZip, dir);
            log.info("GTFS data ready at {}", dir.toAbsolutePath());
        } finally {
            // Always clean up the temp zip, even if extraction failed.
            Files.deleteIfExists(tmpZip);
        }
    }

    // Treat the dataset as present only if every required CSV is on disk.
    private boolean allRequiredFilesPresent(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        for (String name : REQUIRED) {
            if (!Files.isRegularFile(dir.resolve(name))) return false;
        }
        return true;
    }

    // Stream the remote zip straight to disk; ~ MB, so we don't hold it in memory.
    private void download(String url, Path target) throws Exception {
        try (var in = URI.create(url).toURL().openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Downloaded {} ({} bytes)", target.getFileName(), Files.size(target));
    }

    // Unzip the staged file into destDir. Extracts every entry, not just REQUIRED,
    // since the archive is small enough that filtering isn't worth the complexity.
    private void extract(Path zipFile, Path destDir) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                // Resolve the output path and normalize away any "../" segments.
                Path out = destDir.resolve(entry.getName()).normalize();

                // Zip-slip guard: a malicious archive could contain an entry like
                // "../etc/passwd" that, after resolve+normalize, escapes destDir.
                // Refuse anything that no longer lives under destDir.
                if (!out.startsWith(destDir)) {
                    log.warn("Skipping suspicious zip entry {}", entry.getName());
                    continue;
                }

                Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
