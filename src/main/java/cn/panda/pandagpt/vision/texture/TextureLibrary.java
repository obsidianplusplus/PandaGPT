package cn.panda.pandagpt.vision.texture;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 可跨配置重载复用的纹理缓存：资源包列表与缓存大小不变时，已解码的模型与 PNG 不会重新加载。
 *
 * <p>线程：{@link BlockTextures} 与 {@link ResourcePack} 非线程安全，本类用对象锁串行化所有访问，
 * 因此重载交接期间新旧渲染线程不会同时读写缓存。
 */
public final class TextureLibrary implements AutoCloseable {
    /** 在持有锁的情况下使用纹理。 */
    @FunctionalInterface
    public interface TextureTask<T> {
        T run(BlockTextures textures) throws IOException;
    }

    private final List<Path> paths;
    private final int cacheMiB;
    private BlockTextures textures;
    private boolean closed;

    public TextureLibrary(List<Path> paths, int cacheMiB) {
        this.paths = List.copyOf(paths);
        this.cacheMiB = cacheMiB;
    }

    /** 是否与给定配置相同，可直接复用。 */
    public boolean matches(List<Path> paths, int cacheMiB) {
        return this.paths.equals(paths) && this.cacheMiB == cacheMiB;
    }

    /** 提前打开资源包，缩短第一次视觉采样的等待时间。 */
    public synchronized void preload() throws IOException {
        load();
    }

    public synchronized <T> T use(TextureTask<T> task) throws IOException {
        return task.run(load());
    }

    private BlockTextures load() throws IOException {
        if (closed) {
            throw new IOException("Texture library closed");
        }
        if (textures == null) {
            textures = new BlockTextures(new ResourcePack(paths, cacheMiB));
        }
        return textures;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (textures != null) {
            textures.close();
            textures = null;
        }
    }
}
