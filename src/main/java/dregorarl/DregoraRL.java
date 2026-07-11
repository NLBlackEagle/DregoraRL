package dregorarl;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = DregoraRL.MODID, acceptableRemoteVersions = "*")
public class DregoraRL {

	public static final String MODID = "dregorarl";
	public static Logger LOGGER = LogManager.getLogger(MODID);

	@EventHandler
	public void load(FMLInitializationEvent event) {
		try {
			extractWorldFromZip("DregoraRL");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void extractWorldFromZip(String name) throws IOException {
		URL versionSrcFile = this.getClass().getResource("/assets/worldpacker/" + name + "/" + "version.txt");

		if (versionSrcFile == null) {
			throw new FileNotFoundException("No version.txt for world " + name);
		}

		Path dst = Paths.get("./mods/OpenTerrainGenerator/worlds/" + name).toAbsolutePath();
		Path versionDstFile = dst.resolve("version.txt");
		if (Files.exists(dst)) {
			if (Files.exists(versionDstFile)) {
				Version versionSrc = Version.readFrom(versionSrcFile);
				Version versionDst;
				try {
					versionDst = Version.readFrom(versionDstFile.toUri().toURL());
					if (versionDst.compareTo(versionSrc) < 0) {
						LOGGER.info("Existing world {} is up to date {} >= {}", name, versionDst, versionSrc);
						return;
					}
				} catch (IOException | NumberFormatException e) {
					LOGGER.info("Existing world {} has corrupt version file. Updating...", name, e);
				}
			}
		} else {
			Files.createDirectories(dst.getParent());
		}

		try {
			LOGGER.info("Extracting world {}...", name);
			ZipExtractor.extract(Paths.get(URI.create(StringUtils.removeFirst(versionSrcFile.getPath(), "!.*"))), "/assets/worldpacker/" + name, dst);
		} catch (Throwable e) {
			try {
				Files.deleteIfExists(versionDstFile);
			} catch (IOException e1) {
				e.addSuppressed(e1);
			}
			throw e;
		}
	}

}
