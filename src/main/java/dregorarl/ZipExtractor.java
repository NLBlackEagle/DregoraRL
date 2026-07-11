package dregorarl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraftforge.fml.common.ProgressManager;
import net.minecraftforge.fml.common.ProgressManager.ProgressBar;

@SuppressWarnings("unused")
public class ZipExtractor {

	private static final Set<StandardOpenOption> READ_OPTIONS = EnumSet.of(StandardOpenOption.READ);
	private static final Set<StandardOpenOption> WRITE_OPTIONS = EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE);
	private static final Set<StandardOpenOption> READ_WRITE_OPTIONS = EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);

	private static final int EOCDR_MIN_SIZE = 22;
	private static final int EOCDR_SIGNATURE = 0x06054b50;
	private static final int EOCDR_ENTRIES = 10;
	private static final int EOCDR_SIZE = 12;
	private static final int EOCDR_OFFSET = 16;

	private static final int ZEOCDL_SIZE = 20;
	private static final int ZEOCDL_SIGNATURE = 0x07064b50;
	private static final int ZEOCDL_OFFSET = 8;

	private static final int ZEOCDR_MIN_SIZE = 56;
	private static final int ZEOCDR_SIGNATURE = 0x06064b50;
	private static final int ZEOCDR_ENTRIES = 32;
	private static final int ZEOCDR_SIZE = 40;
	private static final int ZEOCDR_OFFSET = 48;

	private static final int CDH_MIN_SIZE = 46;
	private static final int CDH_SIGNATURE = 0x02014b50;
	private static final int CDH_METHOD = 10;
	private static final int CDH_CRC = 16;
	private static final int CDH_CSIZE = 20;
	private static final int CDH_SIZE = 24;
	private static final int CDH_NAME_LENGTH = 28;
	private static final int CDH_EXTRA_LENGTH = 30;
	private static final int CDH_COMMENT_LENGTH = 32;
	private static final int CDH_OFFSET = 42;
	private static final int CDH_NAME = 46;

	private static final int EDF_HEADER_SIZE = 4;
	private static final int EDF_SIZE = 2;
	private static final int ZEIEF_INDICATOR = 0xFFFFFFFF;
	private static final int ZEIEF_ID = 0x0001;
	private static final int ZEIEF_SIZE = 4;
	private static final int ZEIEF_CSIZE = 12;
	private static final int ZEIEF_OFFSET = 20;

	private static final int LFH_MIN_SIZE = 30;
	private static final int LFH_SIGNATURE = 0x04034b50;
	private static final int LFH_NAME_LENGTH = 26;
	private static final int LFH_EXTRA_LENGTH = 28;

	private static final int STORED = 0;
	private static final int DEFLATED = 8;

	private static class EOCDR {

		private final long entries;
		private final long size;
		private final long offset;

		public EOCDR(long entries, long size, long offset) {
			this.entries = entries;
			this.size = size;
			this.offset = offset;
		}

	}

	private static class Entry {

		private final String name;
		private final int method;
		private final int crc;
		private final long csize;
		private final long size;
		private final long headerOffset;
		private final long dataOffset;

		public Entry(String name, int method, int crc, long csize, long size, long headerOffset, long dataOffset) {
			this.name = name;
			this.crc = crc;
			this.method = method;
			this.csize = csize;
			this.size = size;
			this.headerOffset = headerOffset;
			this.dataOffset = dataOffset;
		}

	}

	/**
	 * Extracts the folder denoted by src with all its content in the directory denoted by dst. All files and directories in dst that are not in the src folder of the zip will be deleted.<br>
	 * <br>
	 * This method loads the complete zip file into memory.<br>
	 * 
	 * @param zipFile The zip file from which content will be extracted. The size of the zip file must be {@link Integer#MAX_VALUE} at most. The zip file must contains {@link Integer#MAX_VALUE} entries at most.
	 * @param src The folder inside the zip that will be extracted.
	 * @param dst The directory where the folder will be extracted to.
	 * @throws IllegalArgumentException If the preconditions on the parameters do not hold
	 * @throws ZipException If the zip file is corrupt
	 * @throws UncheckedIOException If some other I/O error occurs during parallel operations
	 * @throws IOException If some other I/O error occurs
	 */
	public static void extract(Path zipFile, String src, Path dst) throws IOException, UncheckedIOException {
		Objects.requireNonNull(zipFile);
		Objects.requireNonNull(src);
		Objects.requireNonNull(dst);
		if (!Files.exists(zipFile)) {
			throw new IllegalArgumentException("zipFile does not exist");
		}
		if (!Files.isRegularFile(zipFile)) {
			throw new IllegalArgumentException("zipFile is not a file");
		}
		if (Files.exists(dst) && !Files.isDirectory(dst)) {
			throw new IllegalArgumentException("dst is not a directory");
		}

		ProgressBar extractProgress = ProgressManager.push("Extracting " + src, 5);
		extractProgress.step("Mapping zip file");

		try (FileChannel zipChannel = FileChannel.open(zipFile, StandardOpenOption.READ)) {
			if (zipChannel.size() > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("zip file too big");
			}

			ByteBuffer zip = zipChannel.map(MapMode.READ_ONLY, 0, zipChannel.size()).order(ByteOrder.LITTLE_ENDIAN);

			extractProgress.step("Reading zip entries");

			EOCDR eocdr = readEOCDR(zip);
			if (eocdr.entries > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("too many entries");
			}

			Set<String> srcDirectories = new ObjectLinkedOpenHashSet<>((int) eocdr.entries / 10);
			Set<String> srcFiles = new ObjectOpenHashSet<>((int) eocdr.entries);
			List<Entry> srcFileEntries = new ObjectArrayList<>((int) eocdr.entries);
			readEntries(zip, eocdr, src, srcDirectories, srcFiles, srcFileEntries);

			extractProgress.step("Deleting old files/directories");

			if (Files.exists(dst)) {
				List<Path> directoriesToDelete = new ObjectArrayList<>();
				List<Path> filesToDelete = new ObjectArrayList<>();
				Files.find(dst, Integer.MAX_VALUE, (p, a) -> {
					String name = dst.relativize(p).toString().replace('\\', '/');
					if (a.isDirectory()) {
						if (!srcDirectories.remove(name)) {
							directoriesToDelete.add(p);
						}
					} else {
						if (!srcFiles.contains(name)) {
							filesToDelete.add(p);
						}
					}
					return false;
				}).count();

				// delete old files
				filesToDelete.parallelStream().forEach(p -> {
					try {
						Files.delete(p);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});

				// delete old directories
				for (Path p : Lists.reverse(directoriesToDelete)) {
					Files.delete(p);
				}
			}

			extractProgress.step("Creating new directories");

			// create new directories
			for (String p : srcDirectories) {
				Files.createDirectory(dst.resolve(p));
			}

			extractProgress.step("Copying new files");

			// extract files
			int batchSize = 1024;
			int batches = (int) Math.ceil((double) srcFileEntries.size() / batchSize);
			ProgressBar copyProgress = ProgressManager.push("Copying files", batches);

			ThreadLocal<ByteBuffer> zipAccessors = ThreadLocal.withInitial(() -> zip.duplicate());
			ThreadLocal<byte[]> arrayCache = ThreadLocal.withInitial(() -> new byte[1 << 16]);
			ThreadLocal<Inflater> inflaterCache = ThreadLocal.withInitial(() -> new Inflater(true));
			ThreadLocal<ByteBuffer> heapBufferCache = ThreadLocal.withInitial(() -> ByteBuffer.allocate(1 << 16));
			IntStream.range(0, batches)
					.parallel()
					.mapToObj(i -> srcFileEntries.subList(i * batchSize, Math.min((i + 1) * batchSize, srcFileEntries.size())))
					.forEach(entries -> {
						entries.forEach(entry -> {
							ByteBuffer entryData = zipAccessors.get();
							entryData.limit((int) entry.dataOffset + (int) entry.csize);
							entryData.position((int) entry.dataOffset);

							try (FileChannel out = FileChannel.open(dst.resolve(entry.name), WRITE_OPTIONS)) {
								long fileSize = out.size();
								if (fileSize > entry.size) {
									out.truncate(fileSize = entry.size);
								}

								switch (entry.method) {
								case STORED:
									while (entryData.hasRemaining()) {
										out.write(entryData);
									}
									break;
								case DEFLATED:
									byte[] array = arrayCache.get();
									Inflater inflater = inflaterCache.get();
									ByteBuffer buffer = heapBufferCache.get();

									try {
										while (entryData.hasRemaining()) {
											int i = Math.min(entryData.remaining(), array.length - 1);
											entryData.get(array, 0, i);
											inflater.setInput(array, 0, i + (entryData.hasRemaining() ? 0 : 1));
											int c;
											while ((c = inflater.inflate(buffer.array())) > 0) {
												buffer.limit(c);
												buffer.position(0);
												while (buffer.hasRemaining()) {
													out.write(buffer);
												}
											}
										}
									} finally {
										inflater.reset();
									}
									break;
								default:
									throw new ZipException("invalid compression method");
								}
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							} catch (DataFormatException e) {
								throw new UncheckedIOException(new IOException(e));
							}
						});

						synchronized (copyProgress) {
							copyProgress.step("");
						}
					});

			ProgressManager.pop(copyProgress);
		}

		ProgressManager.pop(extractProgress);
	}

	private static EOCDR readEOCDR(ByteBuffer zip) throws IOException {
		long pos;
		for (pos = zip.limit() - EOCDR_MIN_SIZE; pos >= zip.position(); pos--) {
			if (getInt(zip, pos) == EOCDR_SIGNATURE) {
				break;
			}
		}
		if (pos < 0) {
			throw new ZipException("end of central directory record not found");
		}

		long entries = getUnsignedShort(zip, pos + EOCDR_ENTRIES);
		long size = getUnsignedInt(zip, pos + EOCDR_SIZE);
		long offset = getUnsignedInt(zip, pos + EOCDR_OFFSET);

		if (pos >= ZEOCDL_SIZE && getInt(zip, pos - ZEOCDL_SIZE) == ZEOCDL_SIGNATURE) {
			pos -= ZEOCDL_SIZE;
			long zeocdrPos = getLong(zip, pos + ZEOCDL_OFFSET);
			if (zeocdrPos < 0 || zeocdrPos + ZEOCDR_MIN_SIZE > pos) {
				throw new ZipException("invalid END header (bad zip64 END offset)");
			}
			pos = zeocdrPos;

			if (getInt(zip, pos) != ZEOCDR_SIGNATURE) {
				throw new ZipException("invalid signature of zip64 end of central directory record");
			}
			entries = getLong(zip, pos + ZEOCDR_ENTRIES);
			size = getLong(zip, pos + ZEOCDR_SIZE);
			offset = getLong(zip, pos + ZEOCDR_OFFSET);
		}

		if (size < 0 || size > pos) {
			throw new ZipException("invalid END header (bad central directory size)");
		}
		if (offset < 0 || offset + size > pos) {
			throw new ZipException("invalid END header (bad central directory offset)");
		}
		if (entries < 0 || entries > size / CDH_MIN_SIZE) {
			throw new ZipException("invalid END header (total entries count too large)");
		}

		return new EOCDR(entries, size, offset);
	}

	private static void readEntries(ByteBuffer zip, EOCDR eocdr, String src, Set<String> srcDirectories, Set<String> srcFiles, List<Entry> srcFileEntries) throws ZipException {
		byte[] prefix = StringUtils.appendIfMissing(StringUtils.removeStart(src, "/"), "/").getBytes(StandardCharsets.UTF_8);
		long p = eocdr.offset;
		for (long i = 0; i < eocdr.entries; i++) {
			if (getInt(zip, p) != CDH_SIGNATURE) {
				throw new ZipException("invalid signature of central directory header");
			}
			int nameLength = getUnsignedShort(zip, p + CDH_NAME_LENGTH);
			int extraLength = getUnsignedShort(zip, p + CDH_EXTRA_LENGTH);
			int commentLength = getUnsignedShort(zip, p + CDH_COMMENT_LENGTH);

			if (nameLength >= prefix.length && equals(zip, p + CDH_NAME, prefix)) {
				if (getUnsignedByte(zip, p + CDH_NAME + nameLength - 1) == '/') {
					srcDirectories.add(decodeFast(zip, p + CDH_NAME + prefix.length, nameLength - prefix.length - 1));
				} else {
					String name = decodeFast(zip, p + CDH_NAME + prefix.length, nameLength - prefix.length);
					int method = getUnsignedShort(zip, p + CDH_METHOD);
					int crc = getInt(zip, p + CDH_CRC);
					long csize = getUnsignedInt(zip, p + CDH_CSIZE);
					long size = getUnsignedInt(zip, p + CDH_SIZE);
					long headerOffset = getUnsignedInt(zip, p + CDH_OFFSET);
					if (csize == ZEIEF_INDICATOR || size == ZEIEF_INDICATOR || headerOffset == ZEIEF_INDICATOR) {
						int o = 0;
						while (o < extraLength) {
							if (getUnsignedShort(zip, p + CDH_NAME + nameLength + o) == ZEIEF_ID) {
								break;
							}
							o += EDF_HEADER_SIZE + getUnsignedShort(zip, p + CDH_NAME + nameLength + o + EDF_SIZE);
						}
						if (o >= extraLength) {
							throw new ZipException("zip64 extended information field not found");
						}
						size = getLong(zip, p + CDH_NAME + nameLength + o + ZEIEF_SIZE);
						csize = getLong(zip, p + CDH_NAME + nameLength + o + ZEIEF_CSIZE);
						headerOffset = getLong(zip, p + CDH_NAME + nameLength + o + ZEIEF_OFFSET);
					}
					if (getInt(zip, headerOffset) != LFH_SIGNATURE) {
						throw new ZipException("invalid signature of local directory header");
					}
					int localNameLength = getUnsignedShort(zip, headerOffset + LFH_NAME_LENGTH);
					int localExtraLength = getUnsignedShort(zip, headerOffset + LFH_EXTRA_LENGTH);
					long dataOffset = headerOffset + LFH_MIN_SIZE + localNameLength + localExtraLength;

					srcFiles.add(name);
					srcFileEntries.add(new Entry(name, method, crc, csize, size, headerOffset, dataOffset));
				}
			}

			p += CDH_MIN_SIZE + nameLength + extraLength + commentLength;
		}
	}

	private static boolean equals(ByteBuffer buf, long pos, byte[] array) {
		for (int i = 0; i < array.length; i++) {
			if (getUnsignedByte(buf, pos + i) != array[i]) {
				return false;
			}
		}
		return true;
	}

	private static String decodeFast(ByteBuffer buf, long pos, int len) {
		if (len <= 0) {
			return "";
		}
		char[] chars = new char[len];
		for (int i = 0; i < len; i++) {
			int b = getUnsignedByte(buf, pos + i);
			if (b < 0) {
				buf = buf.duplicate();
				buf.limit((int) pos + len);
				buf.position((int) pos);
				return StandardCharsets.UTF_8.decode(buf).toString();
			}
			chars[i] = (char) b;
		}
		return new String(chars);
	}

	private static int getUnsignedByte(ByteBuffer buf, long pos) {
		return Byte.toUnsignedInt(buf.get((int) pos));
	}

	private static int getUnsignedShort(ByteBuffer buf, long pos) {
		return Short.toUnsignedInt(buf.getShort((int) pos));
	}

	private static int getInt(ByteBuffer buf, long pos) {
		return buf.getInt((int) pos);
	}

	private static long getUnsignedInt(ByteBuffer buf, long pos) {
		return Integer.toUnsignedLong(buf.getInt((int) pos));
	}

	private static long getLong(ByteBuffer buf, long pos) {
		return buf.getLong((int) pos);
	}

}
