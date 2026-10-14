package cn.panda.pandagpt.vision.texture;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 从 Mojang 官方下载客户端并只提取渲染所需的资源（方块状态、方块模型、方块纹理、颜色表）。
 *
 * <p>只访问 Mojang 官方 HTTPS 主机，逐层校验版本清单给出的 SHA-1 与文件大小；客户端 JAR 只当作 ZIP 读取，绝不加载或执行其中的类。
 * 结果先写入临时文件，校验通过后原子替换目标文件。线程：阻塞调用，只能在后台线程使用。
 */
public final class VanillaAssets {
    public static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Set<String> HOSTS = Set.of(
            "piston-meta.mojang.com", "piston-data.mojang.com", "launcher.mojang.com", "launchermeta.mojang.com");
    private static final Pattern PACK_NAME = Pattern.compile("vanilla-([0-9A-Za-z._-]{1,32})\\.zip");
    private static final String[] PREFIXES = {
        "assets/minecraft/blockstates/",
        "assets/minecraft/models/block/",
        "assets/minecraft/textures/block/",
        "assets/minecraft/textures/colormap/"
    };
    private static final long METADATA_LIMIT = 8L * 1024 * 1024;
    private static final long CLIENT_LIMIT = 128L * 1024 * 1024;
    private static final long ENTRY_LIMIT = 4L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;

    /** 下载结果。 */
    public record Result(String version, int files, Path output) {}

    /**
     * 根据资源包文件名识别要下载的版本，例如 {@code vanilla-26.3.zip} 对应 26.3。
     *
     * @return 文件名不符合约定时为空，表示这是服主自备的资源包，不会自动下载
     */
    public static Optional<String> versionOf(Path pack) {
        Path name = pack.getFileName();
        if (name == null) {
            return Optional.empty();
        }
        Matcher matcher = PACK_NAME.matcher(name.toString());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** 下载并校验指定版本，把渲染资源写入 output。 */
    public static Result download(HttpClient client, String version, Path output)
            throws IOException, InterruptedException {
        Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path work = Files.createTempDirectory(parent, "pandagpt-assets-");
        try {
            JsonObject manifest =
                    json(fetch(client, MANIFEST, work.resolve("manifest.json"), METADATA_LIMIT, null, -1));
            JsonObject entry = null;
            for (JsonElement element : manifest.getAsJsonArray("versions")) {
                JsonObject candidate = element.getAsJsonObject();
                if (version.equals(candidate.get("id").getAsString())) {
                    entry = candidate;
                    break;
                }
            }
            if (entry == null) {
                throw new IOException("version " + version + " is not in the official manifest");
            }
            JsonObject details = json(fetch(
                    client,
                    entry.get("url").getAsString(),
                    work.resolve("version.json"),
                    METADATA_LIMIT,
                    entry.get("sha1").getAsString(),
                    -1));
            JsonObject jar = details.getAsJsonObject("downloads").getAsJsonObject("client");
            String url = jar.get("url").getAsString();
            String sha1 = jar.get("sha1").getAsString();
            Path clientJar = fetch(
                    client,
                    url,
                    work.resolve("client.jar"),
                    CLIENT_LIMIT,
                    sha1,
                    jar.get("size").getAsLong());
            Map<String, String> provenance = new LinkedHashMap<>();
            provenance.put("version", version);
            provenance.put("manifest", MANIFEST);
            provenance.put("client_url", url);
            provenance.put("client_sha1", sha1);
            Path extracted = work.resolve("assets.zip");
            int files = extract(clientJar, extracted, provenance);
            try {
                Files.move(extracted, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(extracted, output, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(version, files, output);
        } finally {
            delete(work);
        }
    }

    private static JsonObject json(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    // 只跟随指向白名单主机的 HTTPS 跳转；边下载边计算 SHA-1 并限制大小。
    private static Path fetch(
            HttpClient client, String url, Path destination, long limit, String expectedSha1, long expectedSize)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        for (int hop = 0; ; hop++) {
            if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost())) {
                throw new IOException("refusing non-official download host " + uri.getHost());
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400 && hop < MAX_REDIRECTS) {
                response.body().close();
                String location = response.headers()
                        .firstValue("Location")
                        .orElseThrow(() -> new IOException("redirect without location"));
                uri = uri.resolve(location);
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new IOException("HTTP " + status);
            }
            MessageDigest digest = sha1();
            long total = 0;
            try (InputStream in = response.body();
                    OutputStream out = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    total += read;
                    if (total > limit) {
                        throw new IOException("download exceeds size limit");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            if (expectedSize >= 0 && total != expectedSize) {
                throw new IOException("size mismatch");
            }
            if (expectedSha1 != null
                    && !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expectedSha1)) {
                throw new IOException("SHA-1 mismatch");
            }
            return destination;
        }
    }

    /** 只复制渲染资源；跳过目录、可疑路径与过大的条目。 */
    static int extract(Path client, Path output, Map<String, String> provenance) throws IOException {
        int count = 0;
        try (ZipFile source = new ZipFile(client.toFile());
                ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(output))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()
                        || !wanted(name)
                        || name.contains("..")
                        || name.contains("\\")
                        || entry.getSize() > ENTRY_LIMIT) {
                    continue;
                }
                byte[] data;
                try (InputStream in = source.getInputStream(entry)) {
                    data = in.readNBytes((int) ENTRY_LIMIT + 1);
                }
                if (data.length > ENTRY_LIMIT) {
                    continue;
                }
                target.putNextEntry(new ZipEntry(name));
                target.write(data);
                target.closeEntry();
                count++;
            }
            target.putNextEntry(new ZipEntry("pandagpt-source.json"));
            target.write(new Gson().toJson(provenance).getBytes(StandardCharsets.UTF_8));
            target.closeEntry();
        }
        if (count < 100) {
            throw new IOException("client contains no render assets");
        }
        return count;
    }

    private static boolean wanted(String name) {
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void delete(Path folder) {
        try (var files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(folder);
        } catch (IOException ignored) {
            // 临时目录残留不影响结果，下次启动不会复用。
        }
    }

    /** 命令行入口，供 CI 准备测试资源：{@code VanillaAssets <版本> <输出文件>}。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: VanillaAssets <version> <output.zip>");
            System.exit(2);
        }
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            Result result = download(client, args[0], Path.of(args[1]));
            System.out.println("verified " + result.version() + ": extracted " + result.files() + " render assets to "
                    + result.output());
        }
    }

    private VanillaAssets() {}
}
