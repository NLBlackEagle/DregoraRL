package dregorarl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

import org.apache.commons.lang3.StringUtils;

class Version implements Comparable<Version> {

	private final int majorVersion;
	private final int minorVersion;
	private final int patchVersion;

	public Version(int majorVersion, int minorVersion, int patchVersion) {
		this.majorVersion = majorVersion;
		this.minorVersion = minorVersion;
		this.patchVersion = patchVersion;
	}

	public static Version readFrom(URL url) throws IOException, NumberFormatException {
		int major = 0;
		int minor = 0;
		int patch = 0;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.startsWith("#"))
					continue;
				if (line.startsWith("MajorVersion:")) {
					major = Integer.parseInt(StringUtils.removeStart(line, "MajorVersion:").trim());
				} else if (line.startsWith("MinorVersion:")) {
					minor = Integer.parseInt(StringUtils.removeStart(line, "MinorVersion:").trim());
				} else if (line.startsWith("PatchVersion:")) {
					patch = Integer.parseInt(StringUtils.removeStart(line, "PatchVersion:").trim());
				}
			}
		}
		return new Version(major, minor, patch);
	}

	@Override
	public String toString() {
		return majorVersion + "." + minorVersion + "." + patchVersion;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Version)) {
			return false;
		}
		return majorVersion == ((Version) obj).majorVersion
				&& minorVersion == ((Version) obj).minorVersion
				&& patchVersion == ((Version) obj).patchVersion;
	}

	@Override
	public int hashCode() {
		int h = 1;
		h = h * 31 + majorVersion;
		h = h * 31 + minorVersion;
		h = h * 31 + patchVersion;
		return h;
	}

	@Override
	public int compareTo(Version o) {
		int r = Integer.compare(majorVersion, o.majorVersion);
		if (r == 0) r = Integer.compare(minorVersion, o.minorVersion);
		if (r == 0) r = Integer.compare(patchVersion, o.patchVersion);
		return r;
	}

}
