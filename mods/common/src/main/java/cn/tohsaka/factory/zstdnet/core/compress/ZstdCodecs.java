/*
 * Copyright (c) 2026 wish (original author, MIT — https://github.com/wish131400/zstdnet)
 * Copyright (c) 2026 xuenai · 麦块联机 / MineKuai (https://minekuai.com)
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is a derivative work of the MIT-licensed ZstdNet by wish. wish's
 * original portions remain under the MIT License (see the LICENSE file); that
 * upstream grant is preserved and not revoked.
 *
 * This project as a whole — and all modifications and additions by xuenai — is
 * licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 4.0
 * International License (CC BY-NC-SA 4.0). You may share and adapt it for
 * NON-COMMERCIAL purposes only, must give appropriate credit and retain the
 * copyright notices above, and must distribute your contributions under this
 * same license (share-alike, source included).
 *
 * You should have received a copy of the license along with ZstdNet.
 * If not, see <https://creativecommons.org/licenses/by-nc-sa/4.0/>.
 */

package cn.tohsaka.factory.zstdnet.core.compress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * zstd-jni 的<b>唯一</b>访问门面：本类以外的代码一律不直接引用 {@code com.github.luben.zstd.*}。
 * <p>
 * 为什么要这一层：zstd-jni 的原生库 JNI 符号名在编译期写死成 {@code Java_com_github_luben_zstd_*}，
 * 一旦把它的 Java 包名重定位（shade/relocate），原生方法就<b>绑不上</b>（{@code UnsatisfiedLinkError}）。
 * 但若把我们打包的那份 {@code com.github.luben.zstd} 直接放进 JPMS 模块路径，又会和宿主自带的同包
 * 撞模块名 / 拆包（如 Mohist / Arclight 把 zstd-jni 注入 boot 模块层，或整合包另带一份）。
 * <p>
 * 解法：我们这份 zstd-jni 以「普通资源 jar」内嵌进 mod jar（路径 {@link #EMBEDDED_RESOURCE}），
 * <b>完全不进模块路径</b>；运行时本门面：
 * <ol>
 *     <li><b>优先复用宿主已有的 zstd-jni</b>——依次在 mod / 线程上下文 / 系统类加载器里探测
 *         {@code com.github.luben.zstd.Zstd}，找到就用宿主那份（其原生库已加载，零冲突）。</li>
 *     <li>宿主没有，才把内嵌资源解压到临时文件，挂到一个<b>隔离的</b>
 *         {@link URLClassLoader} 上加载——包名仍是原始的 {@code com.github.luben.zstd}，
 *         故原生方法能正常绑定；又因它只是个类加载器而非具名模块，不参与 JPMS 模块图，
 *         不会与宿主撞名。</li>
 * </ol>
 * 边界类型一律用引导类加载器里的类型（{@link OutputStream}/{@link InputStream}/{@code byte[]}/{@code long}），
 * zstd 流是 {@code FilterOutputStream}/{@code FilterInputStream} 子类，可直接当 {@code OutputStream}/
 * {@code InputStream} 用，故热路径只在构造时反射一次、读写仍是虚调用。
 */
public final class ZstdCodecs {
    /** 内嵌的原始（未重定位）zstd-jni jar 资源路径；由各重定位变体的构建在 processResources 阶段放入。 */
    private static final String EMBEDDED_RESOURCE = "/cn/tohsaka/factory/zstdnet/embedded/zstd-jni.jar";

    private static final String CLS_ZSTD = "com.github.luben.zstd.Zstd";
    private static final String CLS_OUT = "com.github.luben.zstd.ZstdOutputStream";
    private static final String CLS_IN = "com.github.luben.zstd.ZstdInputStream";
    private static final String CLS_TRAINER = "com.github.luben.zstd.ZstdDictTrainer";
    private static final String CLS_NATIVE = "com.github.luben.zstd.util.Native";

    private static volatile Handles handles;

    private ZstdCodecs() {
    }

    // ---- 对外 API（其余 common 代码只调这些） --------------------------------

    /**
     * 构造压缩流：持续帧（{@code setCloseFrameOnFlush(false)}）+ 可选 LDM + 可选字典。
     * 返回类型用 {@link OutputStream}，使 zstd 具体类型不外泄。
     */
    public static OutputStream newCompressor(OutputStream out, int level, boolean longMatching, int windowLog, byte[] dict) throws IOException {
        Handles h = handles();
        try {
            Object zstdOut = h.outCtor.newInstance(out, level);
            h.outSetCloseFrameOnFlush.invoke(zstdOut, false);
            if (longMatching) {
                h.outSetLong.invoke(zstdOut, windowLog);
            }
            if (dict != null && dict.length > 0) {
                h.outSetDict.invoke(zstdOut, (Object) dict);
            }
            return (OutputStream) zstdOut;
        } catch (InvocationTargetException ex) {
            throw asIO(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IOException("zstdnet: failed to create zstd compressor", ex);
        }
    }

    /**
     * 构造解压流：可选放开大窗口上限（{@code windowLogMax > 0} 才设）+ 可选字典。
     */
    public static InputStream newDecompressor(InputStream in, int windowLogMax, byte[] dict) throws IOException {
        Handles h = handles();
        try {
            Object zstdIn = h.inCtor.newInstance(in);
            if (windowLogMax > 0) {
                h.inSetLongMax.invoke(zstdIn, windowLogMax);
            }
            if (dict != null && dict.length > 0) {
                h.inSetDict.invoke(zstdIn, (Object) dict);
            }
            return (InputStream) zstdIn;
        } catch (InvocationTargetException ex) {
            throw asIO(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IOException("zstdnet: failed to create zstd decompressor", ex);
        }
    }

    /** {@code Zstd.getDictIdFromFrame(byte[])}：data 须为恰好长度的帧头切片。 */
    public static long getDictIdFromFrame(byte[] data) {
        Handles h = handles();
        try {
            return (Long) h.getDictIdFromFrame.invoke(null, (Object) data);
        } catch (InvocationTargetException ex) {
            throw asRuntime(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("zstdnet: Zstd.getDictIdFromFrame failed", ex);
        }
    }

    /** {@code Zstd.getDictIdFromDict(byte[])}。 */
    public static long getDictIdFromDict(byte[] dict) {
        Handles h = handles();
        try {
            return (Long) h.getDictIdFromDict.invoke(null, (Object) dict);
        } catch (InvocationTargetException ex) {
            throw asRuntime(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("zstdnet: Zstd.getDictIdFromDict failed", ex);
        }
    }

    /** 新建一个 {@code ZstdDictTrainer}（不透明句柄，仅供本门面的 trainer 方法消费）。 */
    public static Object newDictTrainer(int sampleBufferBytes, int dictSizeBytes) {
        Handles h = handles();
        try {
            return h.trainerCtor.newInstance(sampleBufferBytes, dictSizeBytes);
        } catch (InvocationTargetException ex) {
            throw asRuntime(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("zstdnet: failed to create ZstdDictTrainer", ex);
        }
    }

    /** {@code ZstdDictTrainer.addSample(byte[])}。 */
    public static boolean dictTrainerAddSample(Object trainer, byte[] sample) {
        Handles h = handles();
        try {
            return (Boolean) h.trainerAddSample.invoke(trainer, (Object) sample);
        } catch (InvocationTargetException ex) {
            throw asRuntime(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("zstdnet: ZstdDictTrainer.addSample failed", ex);
        }
    }

    /** {@code ZstdDictTrainer.trainSamples()}（可能抛 ZstdException 等运行时异常，调用方自行兜底）。 */
    public static byte[] dictTrainerTrain(Object trainer) {
        Handles h = handles();
        try {
            return (byte[]) h.trainerTrainSamples.invoke(trainer);
        } catch (InvocationTargetException ex) {
            throw asRuntime(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("zstdnet: ZstdDictTrainer.trainSamples failed", ex);
        }
    }

    /** {@code Native.isLoaded()}：原生库是否已加载（Android 预加载逻辑用）。 */
    public static boolean isNativeLoaded() {
        Handles h = handles();
        try {
            return (Boolean) h.nativeIsLoaded.invoke(null);
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    /** {@code Native.load()}：在已设置 {@code ZstdNativePath} 后触发原生库加载（Android）。 */
    public static void loadNative() {
        Handles h = handles();
        try {
            h.nativeLoad.invoke(null);
        } catch (InvocationTargetException ex) {
            throw asRuntime(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("zstdnet: Native.load failed", ex);
        }
    }

    // ---- 解析（宿主优先 / 否则隔离加载内嵌资源） ------------------------------

    private static Handles handles() {
        Handles h = handles;
        if (h == null) {
            synchronized (ZstdCodecs.class) {
                h = handles;
                if (h == null) {
                    h = resolve();
                    handles = h;
                }
            }
        }
        return h;
    }

    private static Handles resolve() {
        ClassLoader loader = resolveZstdClassLoader();
        try {
            return new Handles(loader);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                "zstdnet: bundled/host zstd-jni present but its expected API is missing", ex);
        }
    }

    /**
     * 选出能加载 {@code com.github.luben.zstd} 的类加载器。
     * 先在宿主侧多个候选加载器里探测（不初始化，避免提前触发原生加载）；都没有时，
     * 把内嵌资源解压成临时 jar，建一个父为 mod 加载器的隔离 {@link URLClassLoader}。
     */
    private static ClassLoader resolveZstdClassLoader() {
        ClassLoader mod = ZstdCodecs.class.getClassLoader();
        for (ClassLoader candidate : new ClassLoader[]{
            mod,
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader()
        }) {
            if (candidate != null && canLoadZstd(candidate)) {
                return candidate; // 宿主模式：复用已有 zstd-jni（其原生库已/将由宿主加载，零冲突）
            }
        }
        return isolatedLoader(mod); // 隔离模式：从内嵌资源加载我们自带的那份
    }

    private static boolean canLoadZstd(ClassLoader loader) {
        try {
            Class.forName(CLS_ZSTD, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static URLClassLoader isolatedLoader(ClassLoader parent) {
        try (InputStream in = ZstdCodecs.class.getResourceAsStream(EMBEDDED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "zstdnet: no host zstd-jni and embedded resource " + EMBEDDED_RESOURCE + " is missing");
            }
            File temp = File.createTempFile("zstdnet-zstd-jni-", ".jar");
            temp.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
            URL url = temp.toURI().toURL();
            // 父为 mod 加载器，默认 parent-first：隔离模式下父链里没有 zstd（否则早就走宿主模式了），
            // 故 com.github.luben.zstd.* 必从内嵌 jar 加载；其原生库也在该 jar 根目录，由本加载器解压加载。
            return new URLClassLoader(new URL[]{url}, parent);
        } catch (IOException ex) {
            throw new IllegalStateException("zstdnet: failed to stage embedded zstd-jni", ex);
        }
    }

    private static IOException asIO(Throwable cause) {
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        return new IOException(cause);
    }

    private static RuntimeException asRuntime(Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException(cause);
    }

    /** 缓存的反射句柄（构造器 / 方法），一次解析、长期复用。 */
    private static final class Handles {
        final Constructor<?> outCtor;
        final Method outSetCloseFrameOnFlush;
        final Method outSetLong;
        final Method outSetDict;

        final Constructor<?> inCtor;
        final Method inSetLongMax;
        final Method inSetDict;

        final Method getDictIdFromFrame;
        final Method getDictIdFromDict;

        final Constructor<?> trainerCtor;
        final Method trainerAddSample;
        final Method trainerTrainSamples;

        final Method nativeIsLoaded;
        final Method nativeLoad;

        Handles(ClassLoader loader) throws ReflectiveOperationException {
            // initialize=false：仅加载、不触发 <clinit>。zstd 流类的静态块会调 Native.load()，
            // 若在此处提前初始化，会赶在 Android 预加载设置 ZstdNativePath 之前就尝试加载原生库。
            // getConstructor/getMethod 不要求类已初始化；真正的初始化推迟到首次 newInstance/invoke。
            Class<?> zstd = Class.forName(CLS_ZSTD, false, loader);
            Class<?> out = Class.forName(CLS_OUT, false, loader);
            Class<?> in = Class.forName(CLS_IN, false, loader);
            Class<?> trainer = Class.forName(CLS_TRAINER, false, loader);
            Class<?> nativ = Class.forName(CLS_NATIVE, false, loader);

            this.outCtor = out.getConstructor(OutputStream.class, int.class);
            this.outSetCloseFrameOnFlush = out.getMethod("setCloseFrameOnFlush", boolean.class);
            this.outSetLong = out.getMethod("setLong", int.class);
            this.outSetDict = out.getMethod("setDict", byte[].class);

            this.inCtor = in.getConstructor(InputStream.class);
            this.inSetLongMax = in.getMethod("setLongMax", int.class);
            this.inSetDict = in.getMethod("setDict", byte[].class);

            this.getDictIdFromFrame = zstd.getMethod("getDictIdFromFrame", byte[].class);
            this.getDictIdFromDict = zstd.getMethod("getDictIdFromDict", byte[].class);

            this.trainerCtor = trainer.getConstructor(int.class, int.class);
            this.trainerAddSample = trainer.getMethod("addSample", byte[].class);
            this.trainerTrainSamples = trainer.getMethod("trainSamples");

            this.nativeIsLoaded = nativ.getMethod("isLoaded");
            this.nativeLoad = nativ.getMethod("load");
        }
    }
}
